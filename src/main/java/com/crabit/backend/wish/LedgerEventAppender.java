package com.crabit.backend.wish;

public interface LedgerEventAppender {

	LedgerEvent append(LedgerEvent event);
}
