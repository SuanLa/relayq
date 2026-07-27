package com.suanla.relayq.core.service;

import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;

final class DatabaseExceptionClassifier {

    private static final int MYSQL_DUPLICATE_KEY_ERROR_CODE = 1062;

    private DatabaseExceptionClassifier() {
    }

    static boolean isDuplicateKey(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DuplicateKeyException) {
                return true;
            }
            if (current instanceof SQLException sqlException
                    && sqlException.getErrorCode() == MYSQL_DUPLICATE_KEY_ERROR_CODE) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
