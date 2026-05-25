> How could we handle 'agent got stuck' scenarios?

I think we can handle this on a few levels:

- **Kubernetes-level**: liveness probes on the agent pod - Kubernetes can restart the pod if it becomes unhealthy or hangs.
- **Gateway-level**: request timeouts on the `AgentgatewayBackend` in [agentgateway.yaml](releases/agentgateway.yaml) stop waiting for the upstream model response after a timeout - [agentgateway timeout docs](https://agentgateway.dev/docs/kubernetes/2.2.x/resiliency/timeouts/request/).
- **Application-level**: with ADK, `max_iterations` on the agent loop limits how many iterations the agent can perform - [ADK LLM agent lifecycle](https://google.github.io/adk-docs/agents/llm-agents/), [kagent agent concepts](https://kagent.dev/docs/kagent/concepts/agents).

---

> Any automatic timeout/circuit breaker patterns coming out from this framework?

agentgateway has dedicated resiliency primitives, ready to add under `spec.ai.groups[].policies` in [agentgateway.yaml](releases/agentgateway.yaml):

- [Request timeouts](https://agentgateway.dev/docs/kubernetes/2.2.x/resiliency/timeouts/request/) - cut long-running LLM calls
- [Retry policies](https://agentgateway.dev/docs/kubernetes/2.2.x/resiliency/retry/retry/) - retry on transient failures with per-try timeout

---

> How does kgateway handle model failover?

Both kgateway and agentgateway can handle model failover.

Kgateway uses [prioritized model load-balancing](https://kgateway.dev/blog/ai-gateway-load-balancing-model-failover/)

Failover in agentgateway works via the **`spec.ai.groups` provider list** in `AgentgatewayBackend` - multiple providers can be added and it will route to the next on failure - [LLM failover docs](https://agentgateway.dev/docs/kubernetes/2.2.x/llm/failover/).

---

> Can we automatically switch from OpenAI to Claude to local model?

If i got question correctly - i think we can use different providers in `AgentgatewayBackend`.
[Multiple LLMs](https://agentgateway.dev/docs/standalone/main/llm/providers/multiple-llms/)

```yaml
spec:
  ai:
    groups:
    - providers:
      - name: openai
        openai: {}
        policies:
          auth:
            secretRef:
              name: agentgateway-openai
      - name: anthropic
        anthropic: {}
        policies:
          auth:
            secretRef:
              name: agentgateway-anthropic
      - name: local
        openai:
          baseUrl: "http://local-model/v1"
```

---

> Could we seamlessly handle the response formats from these providers?

agentgateway normalizes all provider responses, so application code doesn't need to change when switching providers - [Supported providers](https://agentgateway.dev/docs/kubernetes/2.2.x/llm/about/#supported-providers), [Whats new section here](https://www.solo.io/blog/agentgateway-mcp-authentication-multi-provider-ai)

---

> Can we version the agents built from kagent?

I did not find built in possibilities in kagent, but some practical options could be

- **GitOps**: every change is a git commit → OCI artifact tag, so version history comes from git.
- **Side-by-side**: deploy `db-expert-agent-v2` alongside the original and shift traffic at the `HTTPRoute` level.

---

> Any blue/green or canary deployment patterns for agents?

I think we can use Weighted Traffic Routing, something like this:

```yaml
backendRefs:
- name: db-expert-agent
  port: 8080
  weight: 90
- name: db-expert-agent-v2
  port: 8080
  weight: 10
```

---

> What's the fastmcp-python framework mentioned?

`fastmcp` is a Python library for building MCP servers with a decorator-based API (`@mcp.tool()`), similar to how FastAPI works for HTTP. It abstracts most of the MCP protocol handling.

The demo in [ai-agent.yaml](releases/ai-agent.yaml) shows how simple it is:

```python
from fastmcp import FastMCP
mcp = FastMCP("demo-tools")

@mcp.tool()
def get_current_time() -> str: ...

mcp.run(transport="sse", host="0.0.0.0", port=3000)
```

---

> Is it the easiest path to MCP?

It seems to be one of the simpler ways to get started. `fastmcp` with `uv run --with fastmcp` requires no install step - `uv` pulls the dependency at runtime.

The alternative in the repo is the Java-based [db-mcp](db-mcp/kmcp.yaml), which requires Maven and a full build pipeline - a much heavier setup.

---

> About FinOps: how much control can I have? Token level / per agent / custom cost controls / per-agent budgets?

agentgateway provides most of the core controls at the gateway level:

- [LLM rate limiting](https://agentgateway.dev/docs/kubernetes/2.2.x/llm/rate-limit/) - RPM/TPM caps per route, enforced before the request reaches the provider
- [Budget limits](https://agentgateway.dev/docs/kubernetes/2.2.x/llm/budget-limits/) - token budget caps per backend
- [Cost tracking](https://agentgateway.dev/docs/kubernetes/2.2.x/llm/cost-tracking/) - per-request cost visibility

Additionally Phoenix could be used for metrics visualization, monitoring and alerting

For finer per-agent budget control we could use agentgateway rate limits scoped to a specific `HTTPRoute`

---

> vLLM suitable for agents with many back-and-forth tool calls, or better for single-shot inference?

vLLM is designed for high-throughput batch inference, so its batching advantage becomes smaller for agentic workloads where calls are sequential.

---

> llm-d's scheduler - helps when an agent makes 15 LLM calls?

I’m not really an expert in this area :) , but from the docs it seems the scheduler tries to route requests to nodes that already have related context cached. So for agents making many sequential LLM calls, it can reduce latency.
