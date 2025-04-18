package cc.unitmesh.devti.sketch.ui.patch

import cc.unitmesh.devti.AutoDevBundle
import cc.unitmesh.devti.AutoDevIcons
import cc.unitmesh.devti.settings.coder.coderSetting
import cc.unitmesh.devti.sketch.AutoSketchMode
import cc.unitmesh.devti.sketch.lint.SketchCodeInspection
import cc.unitmesh.devti.sketch.ui.LangSketch
import cc.unitmesh.devti.template.context.TemplateContext
import com.intellij.diff.editor.DiffVirtualFileBase
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.*
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diff.impl.patch.*
import com.intellij.openapi.diff.impl.patch.apply.GenericPatchApplier
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.DarculaColors
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.LocalTimeCounter
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.IOException
import java.nio.charset.Charset
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class SingleFileDiffSketch(
    private val myProject: Project,
    private var currentFile: VirtualFile,
    var patch: TextFilePatch,
    val viewDiffAction: () -> Unit
) : LangSketch {
    private val mainPanel: JPanel = JPanel(VerticalLayout(5))
    private val myHeaderPanel: JPanel = JPanel(BorderLayout())
    private var patchActionPanel: JPanel? = null
    private val oldCode = currentFile.readText()
    private var appliedPatch = try {
        GenericPatchApplier.apply(oldCode, patch.hunks)
    } catch (e: Exception) {
        logger<SingleFileDiffSketch>().warn("Failed to apply patch: ${patch.beforeFileName}", e)
        null
    }

    private val actionPanel = JPanel(HorizontalLayout(4)).apply {
        isOpaque = true
    }

    private val newCode = appliedPatch?.patchedText ?: ""
    private val isAutoRepair = myProject.coderSetting.state.enableAutoRepairDiff

    init {
        val contentPanel = JPanel(BorderLayout())
        val actions = createActionButtons(
            this@SingleFileDiffSketch.currentFile,
            this@SingleFileDiffSketch.appliedPatch,
            this@SingleFileDiffSketch.patch
        )
        
        val fileName = if (currentFile.name.contains("/")) {
            currentFile.name.substringAfterLast("/")
        } else {
            currentFile.name
        }

        val filepathLabel = JBLabel(fileName).apply {
            icon = currentFile.fileType.icon
            border = BorderFactory.createEmptyBorder(2, 10, 2, 10)
            
            val originalColor = foreground
            val hoverColor = JBColor(0x4A7EB3, 0x589DF6) // Blue color for hover state
            
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    FileEditorManager.getInstance(myProject).openFile(currentFile, true)
                }
                
                override fun mouseEntered(e: MouseEvent?) {
                    foreground = hoverColor
                    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 0, hoverColor),
                        BorderFactory.createEmptyBorder(2, 10, 1, 10)
                    )
                }
                
                override fun mouseExited(e: MouseEvent?) {
                    foreground = originalColor
                    cursor = java.awt.Cursor.getDefaultCursor()
                    border = BorderFactory.createEmptyBorder(2, 10, 2, 10)
                }
            })
        }

        val addLine = patch.hunks.sumOf {
            it.lines.count { it.type == PatchLine.Type.ADD }
        }
        val addLabel = JBLabel("+$addLine").apply {
            border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
            foreground = JBColor(0x00FF00, 0x00FF00)
        }

        val removeLine = patch.hunks.sumOf {
            it.lines.count { it.type == PatchLine.Type.REMOVE }
        }
        val removeLabel = JBLabel("-$removeLine").apply {
            border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
            foreground = JBColor(0xFF0000, 0xFF0000)
        }

        val filePanel: JPanel = if (patch.beforeFileName != null) {
            JPanel(BorderLayout()).apply {
                add(filepathLabel, BorderLayout.WEST)
                add(addLabel, BorderLayout.CENTER)
                add(removeLabel, BorderLayout.EAST)
            }
        } else {
            JPanel(BorderLayout()).apply {
                add(filepathLabel, BorderLayout.WEST)
            }
        }

        actions.forEach { button ->
            actionPanel.add(button)
        }

        patchActionPanel = JPanel(BorderLayout()).apply {
            add(filePanel, BorderLayout.WEST)
            add(actionPanel, BorderLayout.EAST)
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        }

        val fileContainer = JPanel(BorderLayout(10, 10)).also {
            it.add(patchActionPanel)
        }
        contentPanel.add(fileContainer, BorderLayout.CENTER)

        mainPanel.add(myHeaderPanel)
        mainPanel.add(contentPanel)
    }

    private fun createActionButtons(
        file: VirtualFile,
        appliedPatch: GenericPatchApplier.AppliedPatch?,
        filePatch: TextFilePatch,
        isRepaired: Boolean = false
    ): List<JButton> {
        val viewButton = JButton(AutoDevBundle.message("sketch.patch.view")).apply {
            icon = AutoDevIcons.View
            toolTipText = AutoDevBundle.message("sketch.patch.action.viewDiff.tooltip")

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    this@SingleFileDiffSketch.viewDiffAction()
                }
            })
        }

        val applyButton = JButton(AutoDevBundle.message("sketch.patch.apply")).apply {
            icon = AutoDevIcons.Run
            toolTipText = AutoDevBundle.message("sketch.patch.action.applyDiff.tooltip")
            isEnabled = !isFailure(appliedPatch)

            addActionListener {
                val document = FileDocumentManager.getInstance().getDocument(file)
                if (document == null) {
                    logger<SingleFileDiffSketch>().error("Document is null for file: ${file.path}")
                    return@addActionListener
                }

                CommandProcessor.getInstance().executeCommand(myProject, {
                    WriteCommandAction.runWriteCommandAction(myProject) {
                        document.setText(appliedPatch!!.patchedText)

                        if (file is DiffVirtualFileBase) {
                            FileEditorManager.getInstance(myProject).closeFile(file)
                        } else {
                            FileEditorManager.getInstance(myProject).openFile(file, true)
                        }
                    }
                }, "ApplyPatch", null)
            }
        }

        val text = if (isRepaired) {
            AutoDevBundle.message("sketch.patch.repaired")
        } else {
            AutoDevBundle.message("sketch.patch.repair")
        }
        val repairButton = JButton(text).apply {
            val isFailedPatch = isFailure(appliedPatch)
            isEnabled = isFailedPatch
            icon = if (isAutoRepair && isFailedPatch) {
                AutoDevIcons.InProgress
            } else {
                AutoDevIcons.Repair
            }

            toolTipText = AutoDevBundle.message("sketch.patch.action.repairDiff.tooltip")
            foreground = if (isEnabled) JBColor(0xFF0000, 0xFF0000) else JPanel().background

            addActionListener {
                FileEditorManager.getInstance(myProject).openFile(file, true)
                val editor = FileEditorManager.getInstance(myProject).selectedTextEditor ?: return@addActionListener

                val failurePatch = if (filePatch.hunks.size > 1) {
                    filePatch.hunks.joinToString("\n") { it.text }
                } else {
                    filePatch.singleHunkPatchText
                }

                DiffRepair.applyDiffRepairSuggestion(myProject, editor, oldCode, failurePatch)
            }
        }

        return listOf(viewButton, applyButton, repairButton)
    }

    private fun isFailure(appliedPatch: GenericPatchApplier.AppliedPatch?): Boolean =
        appliedPatch?.status != ApplyPatchStatus.SUCCESS
                && appliedPatch?.status != ApplyPatchStatus.ALREADY_APPLIED
                && appliedPatch?.status != ApplyPatchStatus.PARTIAL

    override fun getViewText(): String = currentFile.readText()

    override fun updateViewText(text: String, complete: Boolean) {}

    override fun getComponent(): JComponent = mainPanel

    private var isRepaired = false
    override fun onComplete(code: String) {
        if (isRepaired) return
        if (isAutoRepair && appliedPatch?.status != ApplyPatchStatus.SUCCESS) {
            executeAutoRepair {
                runAutoLint(currentFile)
            }
        } else {
            if (myProject.coderSetting.state.enableAutoLintCode && !AutoSketchMode.getInstance(myProject).isEnable) {
                runAutoLint(currentFile)
            }
        }

        isRepaired = true
    }

    fun runAutoLint(file: VirtualFile) {
        ApplicationManager.getApplication().invokeLater {
            val task = object : Task.Backgroundable(myProject, "Analysis code style", false) {
                override fun run(indicator: ProgressIndicator ) {
                    lintCheckForNewCode(file)
                }
            }

            ProgressManager.getInstance()
                .runProcessWithProgressAsynchronously(task, BackgroundableProcessIndicator(task))
        }
    }

    fun lintCheckForNewCode(currentFile: VirtualFile) {
        if (newCode.isEmpty()) return
        val newFile = LightVirtualFile(currentFile, newCode, LocalTimeCounter.currentTime())

        val psiFile = runReadAction { PsiManager.getInstance(myProject).findFile(newFile) } ?: return
        val errors = SketchCodeInspection.runInspections(myProject, psiFile, currentFile, HighlightSeverity.ERROR)
        if (errors.isNotEmpty()) {
            SketchCodeInspection.showErrors(errors, this@SingleFileDiffSketch.mainPanel)
        }
    }

    private fun executeAutoRepair(postAction: () -> Unit) {
        DiffRepair.applyDiffRepairSuggestionSync(myProject, oldCode, newCode, { fixedCode: String ->
            createPatchFromCode(oldCode, fixedCode)?.let { patch ->
                this.patch = patch
                appliedPatch = try {
                    GenericPatchApplier.apply(oldCode, patch.hunks)
                } catch (e: Exception) {
                    logger<SingleFileDiffSketch>().warn("Failed to apply patch: ${patch.beforeFileName}", e)
                    null
                }

                runInEdt {
                    WriteAction.compute<Unit, Throwable> {
                        currentFile.writeText(fixedCode)
                    }
                }

                createActionButtons(currentFile, appliedPatch, patch, isRepaired = true).let { actions ->
                    actionPanel.removeAll()
                    actions.forEach { button ->
                        actionPanel.add(button)
                    }
                }

                postAction()

                mainPanel.revalidate()
                mainPanel.repaint()
            }
        })
    }

    override fun dispose() {}
}

data class DiffRepairContext(
    val intention: String?,
    val patchedCode: String,
    val oldCode: String,
) : TemplateContext

fun VirtualFile.readText(): String {
    return VfsUtilCore.loadText(this)
}

fun createPatchFromCode(oldCode: String, newCode: String): TextFilePatch? {
    val buildPatchHunks: List<PatchHunk> = TextPatchBuilder.buildPatchHunks(oldCode, newCode)
    val textFilePatch = TextFilePatch(Charset.defaultCharset())
    buildPatchHunks.forEach { hunk ->
        textFilePatch.addHunk(hunk)
    }

    return textFilePatch
}


@RequiresWriteLock
fun VirtualFile.writeText(content: String) {
    saveText(this, content)
}

@Throws(IOException::class)
fun saveText(file: VirtualFile, text: String) {
    val charset = file.charset
    runWriteAction {
        file.getOutputStream(file).use { stream ->
            stream.write(text.toByteArray(charset))
        }
    }
}
