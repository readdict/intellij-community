/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.configurationStore

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.StateStorage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.TrackingPathMacroSubstitutor
import com.intellij.openapi.components.impl.stores.StorageUtil
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.*
import org.jdom.Element
import org.jdom.JDOMException
import org.jdom.Parent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

open class FileBasedStorage(file: Path,
                            fileSpec: String,
                            rootElementName: String?,
                            pathMacroManager: TrackingPathMacroSubstitutor? = null,
                            roamingType: RoamingType? = null,
                            provider: StreamProvider? = null) : XmlElementStorage(fileSpec, rootElementName, pathMacroManager, roamingType, provider) {
  private @Volatile var cachedVirtualFile: VirtualFile? = null
  private var lineSeparator: LineSeparator? = null
  private var blockSavingTheContent = false

  @Volatile var file = file
    private set

  init {
    if (ApplicationManager.getApplication().isUnitTestMode && file.toString().startsWith('$')) {
      throw AssertionError("It seems like some macros were not expanded for path: $file")
    }
  }

  protected open val isUseXmlProlog: Boolean = false

  // we never set io file to null
  fun setFile(virtualFile: VirtualFile?, ioFileIfChanged: Path?) {
    cachedVirtualFile = virtualFile
    if (ioFileIfChanged != null) {
      file = ioFileIfChanged
    }
  }

  override fun createSaveSession(states: StateMap) = FileSaveSession(states, this)

  protected open class FileSaveSession(storageData: StateMap, storage: FileBasedStorage) : XmlElementStorage.XmlElementStorageSaveSession<FileBasedStorage>(storageData, storage) {
    override fun save() {
      if (!storage.blockSavingTheContent) {
        super.save()
      }
    }

    override fun saveLocally(element: Element?) {
      if (storage.lineSeparator == null) {
        storage.lineSeparator = if (storage.isUseXmlProlog) LineSeparator.LF else LineSeparator.getSystemLineSeparator()
      }

      val virtualFile = storage.virtualFile
      if (element == null) {
        deleteFile(storage.file, this, virtualFile)
        storage.cachedVirtualFile = null
      }
      else {
        storage.cachedVirtualFile = writeFile(storage.file, this, virtualFile, element, if (storage.isUseXmlProlog) storage.lineSeparator!! else LineSeparator.LF, storage.isUseXmlProlog)
      }
    }
  }

  val virtualFile: VirtualFile?
    get() {
      var result = cachedVirtualFile
      if (result == null) {
        result = LocalFileSystem.getInstance().findFileByPath(file.systemIndependentPath)
        cachedVirtualFile = result
      }
      return cachedVirtualFile
    }

  override fun loadLocalData(): Element? {
    blockSavingTheContent = false

    val attributes: BasicFileAttributes?
    try {
      attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
    }
    catch (e: NoSuchFileException) {
      LOG.debug(e) { "Document was not loaded for $fileSpec, doesn't exists" }
      return null
    }
    catch (e: IOException) {
      processReadException(e)
      return null
    }

    try {
      if (!attributes.isRegularFile) {
        LOG.debug { "Document was not loaded for $fileSpec, not a file" }
      }
      else if (attributes.size() == 0L) {
        processReadException(null)
      }
      else {
        val data = file.readChars()
        lineSeparator = detectLineSeparators(data, if (isUseXmlProlog) null else LineSeparator.LF)
        return loadElement(data)
      }
    }
    catch (e: JDOMException) {
      processReadException(e)
    }
    catch (e: IOException) {
      processReadException(e)
    }
    return null
  }

  private fun processReadException(e: Exception?) {
    val contentTruncated = e == null
    blockSavingTheContent = !contentTruncated && (PROJECT_FILE == fileSpec || fileSpec.startsWith(PROJECT_CONFIG_DIR) || fileSpec == StoragePathMacros.MODULE_FILE || fileSpec == StoragePathMacros.WORKSPACE_FILE)
    if (!ApplicationManager.getApplication().isUnitTestMode && !ApplicationManager.getApplication().isHeadlessEnvironment) {
      if (e != null) {
        LOG.info(e)
      }
      Notification(Notifications.SYSTEM_MESSAGES_GROUP_ID,
        "Load Settings",
        "Cannot load settings from file '$file': ${if (contentTruncated) "content truncated" else e!!.message}\n${if (blockSavingTheContent) "Please correct the file content" else "File content will be recreated"}",
        NotificationType.WARNING)
        .notify(null)
    }
  }

  override fun toString() = file.systemIndependentPath
}

fun writeFile(file: Path?, requestor: Any, virtualFile: VirtualFile?, element: Element, lineSeparator: LineSeparator, prependXmlProlog: Boolean): VirtualFile {
  val result = if (file != null && (virtualFile == null || !virtualFile.isValid)) {
    StorageUtil.getOrCreateVirtualFile(requestor, file)
  }
  else {
    virtualFile!!
  }

  if (LOG.isDebugEnabled || ApplicationManager.getApplication().isUnitTestMode) {
    val content = element.toBufferExposingByteArray(lineSeparator.separatorString)
    if (isEqualContent(result, lineSeparator, content, prependXmlProlog)) {
      throw IllegalStateException("Content equals, but it must be handled not on this level: ${result.name}")
    }
    else if (StorageUtil.DEBUG_LOG != null && ApplicationManager.getApplication().isUnitTestMode) {
      StorageUtil.DEBUG_LOG = "${result.path}:\n$content\nOld Content:\n${LoadTextUtil.loadText(result)}\n---------"
    }
  }

  doWrite(requestor, result, element, lineSeparator, prependXmlProlog)
  return result
}

private val XML_PROLOG = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>".toByteArray()

private fun isEqualContent(result: VirtualFile, lineSeparator: LineSeparator, content: BufferExposingByteArrayOutputStream, prependXmlProlog: Boolean): Boolean {
  val headerLength = if (!prependXmlProlog) 0 else XML_PROLOG.size + lineSeparator.separatorBytes.size
  if (result.length.toInt() != (headerLength + content.size())) {
    return false
  }

  val oldContent = result.contentsToByteArray()

  if (prependXmlProlog && (!ArrayUtil.startsWith(oldContent, XML_PROLOG) || !ArrayUtil.startsWith(oldContent, XML_PROLOG.size, lineSeparator.separatorBytes))) {
    return false
  }

  for (i in headerLength..oldContent.size - 1) {
    if (oldContent[i] != content.internalBuffer[i - headerLength]) {
      return false
    }
  }
  return true
}

private fun doWrite(requestor: Any, file: VirtualFile, content: Any, lineSeparator: LineSeparator, prependXmlProlog: Boolean) {
  LOG.debug { "Save ${file.presentableUrl}" }

  if (!file.isWritable) {
    // may be element is not long-lived, so, we must write it to byte array
    val byteArray = if (content is Element) content.toBufferExposingByteArray(lineSeparator.separatorString) else (content as BufferExposingByteArrayOutputStream)
    throw ReadOnlyModificationException(file, StateStorage.SaveSession { doWrite(requestor, file, byteArray, lineSeparator, prependXmlProlog) })
  }

  runWriteAction {
    file.getOutputStream(requestor).use { out ->
      if (prependXmlProlog) {
        out.write(XML_PROLOG)
        out.write(lineSeparator.separatorBytes)
      }
      if (content is Element) {
        JDOMUtil.writeParent(content, out, lineSeparator.separatorString)
      }
      else {
        (content as BufferExposingByteArrayOutputStream).writeTo(out)
      }
    }
  }
}

internal fun Parent.toBufferExposingByteArray(lineSeparator: String = "\n"): BufferExposingByteArrayOutputStream {
  val out = BufferExposingByteArrayOutputStream(512)
  JDOMUtil.writeParent(this, out, lineSeparator)
  return out
}

internal fun detectLineSeparators(chars: CharSequence, defaultSeparator: LineSeparator?): LineSeparator {
  for (c in chars) {
    if (c == '\r') {
      return LineSeparator.CRLF
    }
    else if (c == '\n') {
      // if we are here, there was no \r before
      return LineSeparator.LF
    }
  }
  return defaultSeparator ?: LineSeparator.getSystemLineSeparator()
}

private fun deleteFile(file: Path, requestor: Any, virtualFile: VirtualFile?) {
  if (virtualFile == null) {
    LOG.warn("Cannot find virtual file $file")
  }

  if (virtualFile == null) {
    if (file.exists()) {
      file.delete()
    }
  }
  else if (virtualFile.exists()) {
    if (virtualFile.isWritable) {
      deleteFile(requestor, virtualFile)
    }
    else {
      throw ReadOnlyModificationException(virtualFile, StateStorage.SaveSession { deleteFile(requestor, virtualFile) })
    }
  }
}

fun deleteFile(requestor: Any, virtualFile: VirtualFile) {
  runWriteAction { virtualFile.delete(requestor) }
}

internal class ReadOnlyModificationException(val file: VirtualFile, val session: StateStorage.SaveSession?) : RuntimeException("File is read-only: "+file)