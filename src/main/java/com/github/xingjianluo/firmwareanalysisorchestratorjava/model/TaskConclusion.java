package com.github.xingjianluo.firmwareanalysisorchestratorjava.model;

/** 任务最终结论（仅在任务正常跑完规则后产生）。 */
public enum TaskConclusion {
    NONE,
    PASS,
    WARN,
    FAIL,
    CONFLICT,
    PARTIAL
}
