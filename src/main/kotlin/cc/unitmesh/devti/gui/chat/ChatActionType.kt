package cc.unitmesh.devti.gui.chat

enum class ChatActionType {
    CHAT,
    REFACTOR,
    EXPLAIN,
    REVIEW,
    CODE_COMPLETE,
    GENERATE_TEST,
    GEN_COMMIT_MESSAGE,
    FIX_ISSUE,
    CREATE_DDL,
    CREATE_CHANGELOG;

    override fun toString(): String {
        return instruction()
    }

    fun instruction(lang: String = ""): String {
        return when (this) {
            EXPLAIN -> "Explain selected $lang code"
            REVIEW -> "Code Review given following $lang code"
            REFACTOR -> "Refactor the given $lang code"
            CODE_COMPLETE -> "Complete $lang code, return rest code, no explaining"
            GENERATE_TEST -> "Write unit test for given $lang code"
            FIX_ISSUE -> "Help me fix this issue"
            GEN_COMMIT_MESSAGE -> """suggest 10 commit messages based on the following diff:
commit messages should:
 - follow conventional commits
 - message format should be: <type>[scope]: <description>

examples:
 - fix(authentication): add password regex pattern
 - feat(storage): add new test cases
 
 {{diff}}
 """
            CREATE_DDL -> "create ddl based on the given information"
            CREATE_CHANGELOG -> "generate release note"
            CHAT -> ""
        }
    }
}
