package com.iflytek.chatgpt.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具的"说明书"
 * <p>
 * 一个工具 = 一个方法（见 {@code agent.tool.TravelTools}）+ 一份给模型看的说明。
 * 这里的 name / description / parameters 会序列化成 OpenAI 兼容的
 * {@code {"type":"function","function":{...}}} 结构，随请求一起发给大模型，
 * 模型据此决定"什么时候调用哪个工具、参数怎么填"。
 */
public class AgentTool {

    /** 函数名，必须与 TravelTools 中的方法一一对应 */
    private final String name;

    /** 模型靠这句话判断何时使用该工具，写得越清楚越准 */
    private final String description;

    /** 参数的 JSON Schema（type=object），描述每个入参的 name / type / description */
    private final ObjectNode parameters;

    public AgentTool(String name, String description, ObjectNode parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ObjectNode getParameters() {
        return parameters;
    }
}
