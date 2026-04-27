package com.github.alexeyleping.intellijdebugmcp.tools.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ReferencesSearch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class PsiToolHandler(private val project: Project) {

    fun handle(tool: String, args: JsonObject): String {
        if (DumbService.isDumb(project)) return "IDE is indexing, please retry in a moment"
        return when (tool) {
            "find_class" -> {
                val name = args["name"]?.jsonPrimitive?.content
                    ?: return "Missing required parameter: name"
                findClass(name)
            }
            "find_usages" -> {
                val name = args["name"]?.jsonPrimitive?.content
                    ?: return "Missing required parameter: name"
                findUsages(name, args["className"]?.jsonPrimitive?.content)
            }
            "get_file_structure" -> {
                val path = args["path"]?.jsonPrimitive?.content
                    ?: return "Missing required parameter: path"
                getFileStructure(path)
            }
            else -> "Unknown tool: $tool"
        }
    }

    private fun findClass(name: String): String {
        return ReadAction.compute<String, Exception> {
            val scope = GlobalSearchScope.projectScope(project)
            val byFqn = JavaPsiFacade.getInstance(project).findClass(name, scope)
            val classes = if (byFqn != null) listOf(byFqn)
                          else PsiShortNamesCache.getInstance(project).getClassesByName(name, scope).toList()

            if (classes.isEmpty()) return@compute "Class not found: $name"

            val base = project.basePath ?: ""
            buildString {
                classes.forEach { cls ->
                    val file = cls.containingFile ?: return@forEach
                    val doc = PsiDocumentManager.getInstance(project).getDocument(file)
                    val line = doc?.getLineNumber(cls.textOffset)?.plus(1) ?: -1
                    val relPath = file.virtualFile?.path?.let { p ->
                        if (base.isNotEmpty()) p.removePrefix("$base/") else p
                    } ?: "unknown"

                    appendLine("Class: ${cls.qualifiedName ?: cls.name}")
                    appendLine("  File: $relPath:$line")

                    val fields = cls.fields
                    if (fields.isNotEmpty()) {
                        appendLine("  Fields (${fields.size}):")
                        fields.forEach { f -> appendLine("    ${f.type.presentableText} ${f.name}") }
                    }

                    val methods = cls.methods
                    if (methods.isNotEmpty()) {
                        appendLine("  Methods (${methods.size}):")
                        methods.forEach { m ->
                            val params = m.parameterList.parameters.joinToString(", ") { p ->
                                "${p.type.presentableText} ${p.name}"
                            }
                            val ret = m.returnType?.presentableText ?: "void"
                            appendLine("    $ret ${m.name}($params)")
                        }
                    }
                }
            }.trim()
        }
    }

    private fun findUsages(name: String, className: String?): String {
        return ReadAction.compute<String, Exception> {
            val scope = GlobalSearchScope.projectScope(project)

            val target: PsiNamedElement? = if (className != null) {
                val cls = JavaPsiFacade.getInstance(project).findClass(className, scope)
                    ?: PsiShortNamesCache.getInstance(project).getClassesByName(className, scope).firstOrNull()
                    ?: return@compute "Class not found: $className"
                cls.findMethodsByName(name, false).firstOrNull()
                    ?: cls.fields.find { it.name == name }
                    ?: return@compute "Member '$name' not found in class '$className'"
            } else {
                JavaPsiFacade.getInstance(project).findClass(name, scope)
                    ?: PsiShortNamesCache.getInstance(project).getClassesByName(name, scope).firstOrNull()
            }

            if (target == null) return@compute "Symbol not found: $name"

            val refs = ReferencesSearch.search(target, scope).findAll()
            if (refs.isEmpty()) return@compute "No usages found for: $name"

            val base = project.basePath ?: ""
            buildString {
                appendLine("Found ${refs.size} usage(s) of '$name':")
                refs.sortedWith(compareBy({ it.element.containingFile?.name }, { it.element.textOffset }))
                    .forEach { ref ->
                        val file = ref.element.containingFile ?: return@forEach
                        val doc = PsiDocumentManager.getInstance(project).getDocument(file)
                        val line = doc?.getLineNumber(ref.element.textOffset)?.plus(1) ?: -1
                        val relPath = file.virtualFile?.path?.let { p ->
                            if (base.isNotEmpty()) p.removePrefix("$base/") else p
                        } ?: file.name
                        val lineText = doc?.text?.lines()?.getOrNull(line - 1)?.trim() ?: ""
                        appendLine("  $relPath:$line: $lineText")
                    }
            }.trim()
        }
    }

    private fun findClassesInFile(vf: VirtualFile): List<PsiClass> {
        val fileScope = GlobalSearchScope.fileScope(project, vf)
        val cache = PsiShortNamesCache.getInstance(project)
        return cache.getAllClassNames().flatMap { name ->
            cache.getClassesByName(name, fileScope).toList()
        }
    }

    private fun getFileStructure(path: String): String {
        return ReadAction.compute<String, Exception> {
            val base = project.basePath ?: ""
            val vf = LocalFileSystem.getInstance().findFileByPath(path)
                ?: LocalFileSystem.getInstance().findFileByPath("$base/$path")
                ?: return@compute "File not found: $path"

            val psiFile = PsiManager.getInstance(project).findFile(vf)
                ?: return@compute "Cannot parse file: $path"

            val classes = findClassesInFile(vf)
            if (classes.isEmpty()) return@compute "No classes found in: $path"

            val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            buildString {
                appendLine("Structure of $path:")
                classes.forEach { cls ->
                    val line = doc?.getLineNumber(cls.textOffset)?.plus(1) ?: -1
                    appendLine("  class ${cls.name} (line $line)")
                    cls.fields.forEach { f ->
                        val fLine = doc?.getLineNumber(f.textOffset)?.plus(1) ?: -1
                        appendLine("    field  ${f.type.presentableText} ${f.name} (line $fLine)")
                    }
                    cls.methods.forEach { m ->
                        val mLine = doc?.getLineNumber(m.textOffset)?.plus(1) ?: -1
                        val params = m.parameterList.parameters.joinToString(", ") { p ->
                            "${p.type.presentableText} ${p.name}"
                        }
                        val ret = m.returnType?.presentableText ?: "void"
                        appendLine("    method $ret ${m.name}($params) (line $mLine)")
                    }
                }
            }.trim()
        }
    }
}
