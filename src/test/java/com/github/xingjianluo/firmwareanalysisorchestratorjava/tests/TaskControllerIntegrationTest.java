package com.github.xingjianluo.firmwareanalysisorchestratorjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Base64;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 只用本地样本跑通 REST 链路，验证状态/错误码可经 HTTP 查询。 */
@SpringBootTest
class TaskControllerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TaskControllerIntegrationTest.class);

    @Autowired
    private WebApplicationContext wac;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(wac).build();
    }

    private static String b64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static String submitJson(String fileName, byte[] data, String key) {
        String k = key == null ? "null" : ("\"" + key + "\"");
        return "{\"fileName\":\"" + fileName + "\",\"contentBase64\":\"" + b64(data)
                + "\",\"idempotencyKey\":" + k + "}";
    }

    @Test
    void submitQueryAndMalformedOverHttp() throws Exception {
        MockMvc mvc = mvc();
        MvcResult created = mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("device.fwimg", Samples.cleanFirmware(), "k1")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").exists())
                .andExpect(jsonPath("$.status").value(
                        org.hamcrest.Matchers.anyOf(
                                org.hamcrest.Matchers.is("QUEUED"),
                                org.hamcrest.Matchers.is("RUNNING"),
                                org.hamcrest.Matchers.is("COMPLETED"))))
                .andReturn();
        String body = created.getResponse().getContentAsString();
        String taskId = body.replaceAll(".*\"taskId\":\"([^\"]+)\".*", "$1");
        log.info("HTTP提交成功 taskId={}", taskId);

        // 轮询到终态并验证结论
        String status;
        long deadline = System.currentTimeMillis() + 10_000;
        do {
            status = mvc.perform(get("/api/tasks/" + taskId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()
                    .replaceAll(".*\"status\":\"([^\"]+)\".*", "$1");
        } while (!TERMINAL.contains(status) && System.currentTimeMillis() < deadline);
        mvc.perform(get("/api/tasks/" + taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conclusion").value("PASS"));
        log.info("HTTP查询完成 taskId={} status={}", taskId, status);

        // 相同幂等键重复提交：同一条任务，submitCount 增加
        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("device.fwimg", Samples.cleanFirmware(), "k1")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.deduplicated").value(true));

        // 畸形样本：受理后异步失败，失败原因经查询接口以稳定错误码暴露（非底层异常）
        MvcResult bad = mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("bad.elf", Samples.cleanFirmware(), "k2")))
                .andExpect(status().isAccepted())
                .andReturn();
        String badId = bad.getResponse().getContentAsString()
                .replaceAll(".*\"taskId\":\"([^\"]+)\".*", "$1");
        waitFor(mvc, badId, "FAILED");
        mvc.perform(get("/api/tasks/" + badId))
                .andExpect(jsonPath("$.failureCode").value("EXTENSION_MISMATCH"))
                .andExpect(jsonPath("$.failureMessage").value("文件扩展名与真实格式不符"));
        log.info("畸形样本异步失败 taskId={} reason=EXTENSION_MISMATCH 错误来源=样本解析（不透出底层异常）",
                badId);

        // 不存在任务
        mvc.perform(get("/api/tasks/no-such-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    private static final Set<String> TERMINAL =
            Set.of("COMPLETED", "FAILED", "PARTIAL_FAILED", "CANCELLED");

    private static void waitFor(MockMvc mvc, String taskId, String expectedStatus) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        String status;
        do {
            status = mvc.perform(get("/api/tasks/" + taskId))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()
                    .replaceAll(".*\"status\":\"([^\"]+)\".*", "$1");
            if (status.equals(expectedStatus)) {
                return;
            }
            Thread.sleep(5);
        } while (!TERMINAL.contains(status) && System.currentTimeMillis() < deadline);
        org.junit.jupiter.api.Assertions.assertEquals(expectedStatus, status,
                "任务 " + taskId + " 未进入预期状态");
    }
}
