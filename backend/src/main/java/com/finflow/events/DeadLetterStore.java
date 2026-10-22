package com.finflow.events;

import com.finflow.common.web.PageResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterStore {

    private final JdbcTemplate jdbc;

    public DeadLetterStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record DeadLetter(long id, String topic, int partition, long offset, String key, String payload,
                             String originalTopic, String consumerGroup, String errorClass, String errorMessage,
                             Instant receivedAt, int replayCount, Instant lastReplayedAt) {
    }

    private static final RowMapper<DeadLetter> MAPPER = (rs, i) -> new DeadLetter(rs.getLong("id"),
            rs.getString("topic"), rs.getInt("kafka_partition"), rs.getLong("kafka_offset"), rs.getString("message_key"),
            rs.getString("payload"), rs.getString("original_topic"), rs.getString("consumer_group"),
            rs.getString("error_class"), rs.getString("error_message"), rs.getTimestamp("received_at").toInstant(),
            rs.getInt("replay_count"),
            rs.getTimestamp("last_replayed_at") == null ? null : rs.getTimestamp("last_replayed_at").toInstant());

    /** Idempotent on the DLT position, so a redelivered dead letter is recorded once. */
    public void record(String topic, int partition, long offset, String key, String payload, String originalTopic,
                       String consumerGroup, String errorClass, String errorMessage) {
        jdbc.update("""
                insert into dead_letters (topic, kafka_partition, kafka_offset, message_key, payload, original_topic,
                    consumer_group, error_class, error_message, received_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (topic, kafka_partition, kafka_offset) do nothing""",
                topic, partition, offset, key, payload, originalTopic, consumerGroup, errorClass,
                errorMessage == null || errorMessage.length() <= 1000 ? errorMessage : errorMessage.substring(0, 1000),
                Timestamp.from(Instant.now()));
    }

    public PageResponse<DeadLetter> list(int page, int size) {
        Long total = jdbc.queryForObject("select count(*) from dead_letters", Long.class);
        List<DeadLetter> content = jdbc.query("select * from dead_letters order by id desc limit ? offset ?", MAPPER,
                size, (long) page * size);
        long t = total == null ? 0 : total;
        return new PageResponse<>(content, page, size, t, (int) Math.ceil(t / (double) size));
    }

    public Optional<DeadLetter> find(long id) {
        return jdbc.query("select * from dead_letters where id = ?", MAPPER, id).stream().findFirst();
    }

    public void markReplayed(long id) {
        jdbc.update("update dead_letters set replay_count = replay_count + 1, last_replayed_at = now() where id = ?", id);
    }
}
