package models

enum class AgentRole {
    PLANNER,        // Планировщик - составляет план
    EXECUTOR,       // Исполнитель - выполняет шаги
    VALIDATOR,      // Валидатор - проверяет результаты
    COORDINATOR,    // Координатор - управляет другими агентами
    OBSERVER        // Наблюдатель - мониторит процесс
}

data class Agent(
    val id: String,
    val role: AgentRole,
    val name: String,
    val systemPrompt: String,
    val capabilities: List<String>,
    val isActive: Boolean = true
)

data class AgentTask(
    val id: String,
    val agentId: String,
    val description: String,
    var status: AgentTaskStatus = AgentTaskStatus.PENDING,
    val result: String? = null,
    val assignedAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null
)

enum class AgentTaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    BLOCKED
}

data class AgentMessage(
    val fromAgentId: String,
    val toAgentId: String,
    val content: String,
    val type: AgentMessageType,
    val timestamp: Long = System.currentTimeMillis()
)

enum class AgentMessageType {
    TASK_ASSIGNMENT,
    STATUS_UPDATE,
    QUERY,
    RESPONSE,
    BLOCK,
    UNBLOCK,
    NOTIFICATION
}