package com.example.inventory.management.inventory.common.exception;

import java.util.Collection;

public class InvalidSortPropertyException extends InventoryException {

    public InvalidSortPropertyException(String property, Collection<String> allowed) {
        super(ErrorCode.VALIDATION_FAILED,
                "sort: 허용되지 않은 정렬 속성입니다. property=" + property + ", 허용=" + allowed);
    }
}
