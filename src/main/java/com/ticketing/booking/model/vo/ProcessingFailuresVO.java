package com.ticketing.booking.model.vo;

import java.util.List;

public record ProcessingFailuresVO(List<ProcessingFailureVO> items, int page, int size,
        boolean hasNext, ProcessingBacklogVO backlog, long invalidMessageCount) { }
