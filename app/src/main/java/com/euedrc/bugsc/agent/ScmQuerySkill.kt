package com.euedrc.bugsc.agent

class ScmQuerySkill(
    client: RemoteQueryClient,
    isScmLoggedIn: () -> Boolean,
) : RemoteQuerySkill(
    id = "scm_query",
    name = "SCM 查询 API",
    client = client,
    isAuthenticated = isScmLoggedIn,
    matchingIntents = setOf(AgentIntent.MARKET),
)
