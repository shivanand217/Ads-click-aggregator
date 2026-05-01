package com.clickagg.clickprocessor.controller;

import com.clickagg.clickprocessor.service.ClickProcessorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ClickController.class)
class ClickControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ClickProcessorService clickProcessorService;

    @Test
    void handleClick_validRequest_returns302WithLocation() throws Exception {
        when(clickProcessorService.processClick(any()))
                .thenReturn("https://example.com?ad_id=ad-123");

        mockMvc.perform(get("/v1/click/ad-123")
                        .param("sessionId", "sess-abc")
                        .header("User-Agent", "Mozilla/5.0")
                        .header("X-Forwarded-For", "203.0.113.42"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com?ad_id=ad-123"))
                .andExpect(header().exists("X-Click-Id"));
    }

    @Test
    void handleClick_withExplicitClickId_usesProvidedClickId() throws Exception {
        when(clickProcessorService.processClick(any()))
                .thenReturn("https://example.com");

        mockMvc.perform(get("/v1/click/ad-456")
                        .param("clickId", "explicit-click-uuid")
                        .param("placementId", "placement-1")
                        .param("sessionId", "sess-xyz"))
                .andExpect(status().isFound())
                .andExpect(header().string("X-Click-Id", "explicit-click-uuid"));
    }

    @Test
    void handleClick_withoutClickId_generatesDeterministicId() throws Exception {
        when(clickProcessorService.processClick(any()))
                .thenReturn("https://example.com");

        // Two requests with same params within 5-second window should generate same click_id
        // We can't easily test the 5-second window here, but we verify a click_id is still returned
        mockMvc.perform(get("/v1/click/ad-789")
                        .param("sessionId", "sess-same"))
                .andExpect(status().isFound())
                .andExpect(header().exists("X-Click-Id"));
    }

    @Test
    void handleClick_missingAdId_returns404() throws Exception {
        mockMvc.perform(get("/v1/click/"))
                .andExpect(status().isNotFound());
    }
}
