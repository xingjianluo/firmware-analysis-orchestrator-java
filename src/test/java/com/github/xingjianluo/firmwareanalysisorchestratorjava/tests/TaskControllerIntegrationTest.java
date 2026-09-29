package com.github.xingjianluo.firmwareanalysisorchestratorjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 层冒烟：提交、重复提交去重、失败原因透传为稳定码、查询与复现。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String b64(byte[] content) {
        return Base64.getEncoder().encodeToString(content);
    }

    private static MvcResult waitForVerdict(MockMvc mockMvc, String taskId, String verdict)
            throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        MvcResult last = null;
        while (System.currentTimeMillis() < deadline) {
            last = mockMvc.perform(get("/api/tasks/" + taskId)).andExpect(status().isOk()).andReturn();
            String body = last.getResponse().getContentAsString();
            if (body.contains("\"verdict\":\"" + verdict + "\"")
                    || body.contains("\"status\":\"FAILED\"")
                    || body.contains("\"status\":\"PARTIAL_FAILED\"")
                    || body.contains("\"status\":\"CANCELLED\"")) {
                return last;
            }
            Thread.sleep(20);
        }
        return last;
    }

    @Test
    void submitQueryAndDuplicateOverHttp() throws Exception {
        String body = "{\"taskId\":\"http-valid\",\"declaredExtension\":\"bin\",\"contentBase64\":\""
                + b64(SampleFixtures.validFw1()) + "\"}";
        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.duplicate").value(false));

        waitForVerdict(mockMvc, "http-valid", "PASS");
        mockMvc.perform(get("/api/tasks/http-valid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.verdict").value("PASS"))
                .andExpect(jsonPath("$.detectedFormat").value("FW1"));

        // 重复提交：200 + duplicate=true，不重复执行
        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(post("/api/tasks/http-valid/reproduce"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.storedVerdict").value("PASS"));
    }

    @Test
    void malformedSampleExposesStableFailureCodeNotRawException() throws Exception {
        String truncated = "{\"taskId\":\"http-truncated\",\"declaredExtension\":\"fw1\",\"contentBase64\":\""
                + b64(SampleFixtures.fw1Truncated(SampleFixtures.metadata("device", "x"), 3)) + "\"}";
        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(truncated))
                .andExpect(status().isAccepted());
        waitForVerdict(mockMvc, "http-truncated", "X-NONE");
        mockMvc.perform(get("/api/tasks/http-truncated"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("TRUNCATED_SAMPLE"));
    }

    @Test
    void invalidBase64Returns400AndUnknownTask404() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":\"http-bad\",\"contentBase64\":\"@@not-base64@@\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/tasks/http-missing/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TASK_NOT_FOUND"));
    }
}
