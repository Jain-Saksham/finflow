package com.finflow.common.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class Paging {

    public static final String DEFAULT_SIZE = "20";
    public static final int MAX_SIZE = 100;

    private Paging() {
    }

    /** Newest first; callers validate page/size bounds with Bean Validation on the controller. */
    public static Pageable newestFirst(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    }
}
