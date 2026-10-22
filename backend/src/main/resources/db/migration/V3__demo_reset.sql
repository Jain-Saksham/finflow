-- Demo reset: the ledger stays append-only for every real merchant. A DELETE is permitted only when
-- the transaction explicitly sets finflow.allow_demo_reset = 'on' AND the row belongs to a merchant
-- flagged demo = true. A reset can therefore never erase a non-demo merchant's ledger, whichever
-- code path issues the statement.
create or replace function ledger_forbid_mutation() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'UPDATE'
        and current_setting('finflow.allow_ledger_backdating', true) = 'on'
        and (to_jsonb(new) - 'created_at') = (to_jsonb(old) - 'created_at') then
        return new;
    end if;
    if tg_op = 'DELETE'
        and current_setting('finflow.allow_demo_reset', true) = 'on'
        and exists (select 1 from merchants m where m.id = old.merchant_id and m.demo) then
        return old;
    end if;
    raise exception 'ledger_entries is append-only: % is not allowed', tg_op using errcode = '42501';
end
$$;
