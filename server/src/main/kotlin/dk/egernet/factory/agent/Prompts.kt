package dk.egernet.factory.agent

/** Role system prompts live in `src/main/resources/prompts/<role>.md`. */
class Prompts(private val org: String) {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun role(role: String): String = cache.computeIfAbsent(role) {
        javaClass.getResourceAsStream("/prompts/$role.md")?.bufferedReader()?.readText()
            ?: error("Missing prompt resource prompts/$role.md")
    }

    /**
     * Appended to Claude Code's default system prompt via `--append-system-prompt`. [workspace]: null for a project
     * that is one repository; for a workspace project the workspace sentence replaces "Your working directory is a git
     * checkout of it", followed by [workspace] (a chat's list of members; empty for pipeline agents).
     */
    fun system(role: String, repo: String, mayInstallSoftware: Boolean, workspace: String? = null): String = buildString {
        append(role(role).trim())
        append("\n\n## Environment\n\n")
        append("You are running inside Agern, a self-hosted software factory. The GitHub organization is `")
        append(org).append("` and this agent works on the repository `").append(org).append('/').append(repo).append("`. ")
        if (workspace == null) append("Your working directory is a git checkout of it.")
        else append(
            "This project is a workspace: your working directory is a checkout of the workspace repository `$org/$repo`, " +
                "and its member repositories are separate git repositories in sub-directories. Issues live in the workspace repository.",
        )
        append(" Talk to the factory through the `factory_*` MCP tools.\n")
        if (!workspace.isNullOrBlank()) append(workspace.trimEnd()).append('\n')
        append("Write everything that ends up in the repository or on GitHub (specs, code comments, commit messages, ")
        append("summaries) in English, unless the issue itself is written in another language; then use that language.\n")
        if (!mayInstallSoftware) {
            append("\n## Software installation policy\n\n")
            append("Do NOT install system-wide software (no `brew install`, `apt-get`, `sudo`, global `npm -g`/`pip install --user`, ")
            append("curl-piped installers, or changes outside your working directory and home). Project-local dependencies ")
            append("declared by the repository (e.g. `npm install`, `./gradlew`, a virtualenv inside the checkout) are fine. ")
            append("If you need a tool that is not installed, call `factory_request_software` with its name and why you need it, ")
            append("tell the user, and continue with what you can do without it.\n")
        }
    }
}
