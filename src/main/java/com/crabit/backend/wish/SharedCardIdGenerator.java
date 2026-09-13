package com.crabit.backend.wish;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Identity allocation is separate from card state and synchronization. */
@Component
public class SharedCardIdGenerator {
    public UUID nextId() { return UUID.randomUUID(); }
}
