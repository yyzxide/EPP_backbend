package com.epp.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminTokenInterceptorTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AdminTokenInterceptor interceptor = new AdminTokenInterceptor();
        ReflectionTestUtils.setField(interceptor, "adminToken", "secret-admin-token");
        mockMvc = MockMvcBuilders.standaloneSetup(new TestAdminController())
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    void shouldAllowStrategyGetWithoutAdminToken() throws Exception {
        mockMvc.perform(get("/api/strategy/S1001"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectProtectedRequestWithoutAdminToken() throws Exception {
        mockMvc.perform(put("/api/strategy/S1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configJson\":\"{}\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowProtectedRequestWithAdminToken() throws Exception {
        mockMvc.perform(post("/api/message/dispatch")
                        .header(AdminTokenInterceptor.ADMIN_TOKEN_HEADER, "secret-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"HEARTBEAT\"}"))
                .andExpect(status().isOk());
    }

    @RestController
    static class TestAdminController {
        @GetMapping("/api/strategy/{strategyId}")
        public String getStrategy(@PathVariable String strategyId) {
            return strategyId;
        }

        @PutMapping("/api/strategy/{strategyId}")
        public String updateStrategy(@PathVariable String strategyId) {
            return strategyId;
        }

        @PostMapping("/api/message/dispatch")
        public String dispatch() {
            return "ok";
        }
    }
}
