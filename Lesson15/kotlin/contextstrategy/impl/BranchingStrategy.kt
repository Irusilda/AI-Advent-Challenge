package contextstrategy.impl

import contextstrategy.ContextStrategy
import models.Message

class BranchingStrategy(
    systemMessage: Message
) : ContextStrategy {
    data class Branch(
        val id: String,
        val messages: MutableList<Message>
    )

    private val branches = mutableMapOf<String, Branch>()
    private var currentBranchId: String = "main"
    private val systemMsg = systemMessage

    init {
        val mainBranch = Branch("main", mutableListOf(systemMsg))
        branches["main"] = mainBranch
    }

    override fun addUserMessage(message: Message) {
        currentBranch().messages.add(message)
    }

    override fun addAssistantMessage(message: Message) {
        currentBranch().messages.add(message)
    }

    override fun buildContext(): List<Message> = currentBranch().messages.toList()

    override fun clear() {
        branches.clear()
        val mainBranch = Branch("main", mutableListOf(systemMsg))
        branches["main"] = mainBranch
        currentBranchId = "main"
    }

    override fun loadHistory(messages: List<Message>) {
        clear()
        currentBranch().messages.addAll(messages)
    }

    override fun getHistory(): List<Message> = currentBranch().messages.toList()

    override fun getName(): String = "Branching(current=$currentBranchId, branches=${branches.size})"

    private fun currentBranch(): Branch = branches[currentBranchId] ?: branches["main"]!!

    /** Создать checkpoint (сохранить текущий момент) – по сути, вернуть указатель на текущую длину истории */
    fun createCheckpoint(): Int = currentBranch().messages.size

    /** Создать новую ветку от заданного checkpoint (индекса сообщений в текущей ветке) */
    fun createBranchFromCheckpoint(branchName: String, checkpointIndex: Int): Boolean {
        if (branches.containsKey(branchName)) return false
        val sourceMessages = currentBranch().messages
        if (checkpointIndex < 0 || checkpointIndex > sourceMessages.size) return false
        val newMessages = sourceMessages.subList(0, checkpointIndex).toMutableList()
        branches[branchName] = Branch(branchName, newMessages)
        return true
    }

    /** Переключиться на другую ветку */
    fun switchToBranch(branchName: String): Boolean {
        if (!branches.containsKey(branchName)) return false
        currentBranchId = branchName
        return true
    }

    /** Получить список имён веток */
    fun listBranches(): List<String> = branches.keys.toList()

    /** Удалить ветку (кроме main) */
    fun deleteBranch(branchName: String): Boolean {
        if (branchName == "main") return false
        return branches.remove(branchName) != null
    }
}