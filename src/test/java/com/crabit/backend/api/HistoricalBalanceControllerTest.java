package com.crabit.backend.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.crabit.backend.history.HistoricalBalanceException;
import com.crabit.backend.history.HistoricalBalanceQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HistoricalBalanceControllerTest {
    private final HistoricalBalanceQueryService history=mock(HistoricalBalanceQueryService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(new HistoricalBalanceController(history)).build();
    private static final String PATH="/internal/v1/academies/00000000-0000-4000-8000-000000000001/students/00000000-0000-4000-8000-000000000002/card-balance-accounts/00000000-0000-4000-8000-000000000003/historical-balances";
    @Test void checksMachineAttributeAgainAndSanitizesBothServerFailures() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
        verifyNoInteractions(history);
        for(var code:new HistoricalBalanceException.Code[]{HistoricalBalanceException.Code.HISTORICAL_BALANCE_INTEGRITY_ERROR,HistoricalBalanceException.Code.HISTORICAL_BALANCE_QUERY_UNAVAILABLE}){
            doThrow(new HistoricalBalanceException(code)).when(history).query(any(),any(),any(),any(),any(),any(),any());
            mvc.perform(get(PATH).requestAttr("crabit.machine-behavior-authenticated",true).param("fromDate","2026-09-01").param("toDateExclusive","2026-09-02").param("granularity","DAY"))
                    .andExpect(status().is(code.status)).andExpect(header().string("Cache-Control","no-store"))
                    .andExpect(jsonPath("$.error.code").value(code.name())).andExpect(jsonPath("$.error.retryable").value(code.retryable))
                    .andExpect(jsonPath("$.error.fieldErrors").isEmpty()).andExpect(jsonPath("$.error.details").isEmpty());
        }
    }
}
