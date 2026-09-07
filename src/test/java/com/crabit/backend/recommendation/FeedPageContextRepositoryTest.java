package com.crabit.backend.recommendation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class FeedPageContextRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FeedPageContextRepository repository =
            new FeedPageContextRepository(jdbc, new ObjectMapper());

    @Test
    void requestMetadataStorageOutageUsesContractException() {
        when(jdbc.queryForObject(anyString(), any(Class.class), any()))
                .thenThrow(new DataAccessResourceFailureException("private database detail"));

        assertThatThrownBy(() -> repository.requestId(UUID.randomUUID()))
                .isInstanceOf(FeedPageContextRepository.ContextUnavailable.class)
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void rankedIdsTransientStorageOutageUsesContractException() {
        when(jdbc.queryForObject(anyString(), any(Class.class), any()))
                .thenThrow(new TransientDataAccessResourceException("private database detail"));

        assertThatThrownBy(() -> repository.rankedIds(UUID.randomUUID()))
                .isInstanceOf(FeedPageContextRepository.ContextUnavailable.class)
                .hasCauseInstanceOf(TransientDataAccessResourceException.class);
    }
}
