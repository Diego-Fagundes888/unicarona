package com.unicarona.shared.dto;

import java.util.List;

public record PageDto<T>(List<T> items,Integer nextOffset) {
    public static <T> PageDto<T> from(List<T> rows,int limit,int offset) {
        return new PageDto<>(List.copyOf(rows.subList(0,Math.min(limit,rows.size()))),rows.size()>limit?offset+limit:null);
    }
}
