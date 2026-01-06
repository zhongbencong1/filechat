<template>
  <div class="home-container">
    <el-container>
      <!-- 侧边栏：文件列表 -->
      <el-aside width="300px" class="sidebar">
        <div class="sidebar-header">
          <h3>我的文档</h3>
          <el-upload
            :action="uploadAction"
            :headers="uploadHeaders"
            :on-success="handleUploadSuccess"
            :on-error="handleUploadError"
            :before-upload="beforeUpload"
            :show-file-list="false"
            :disabled="uploading"
          >
            <el-button type="primary" :loading="uploading" :icon="Upload">
              {{ uploading ? '上传中...' : '上传文档' }}
            </el-button>
          </el-upload>
        </div>
        <el-scrollbar height="calc(100vh - 120px)">
          <el-empty v-if="fileList.length === 0" description="暂无文档" />
          <div v-else class="file-list">
            <div
              v-for="file in fileList"
              :key="file.id"
              :class="['file-item', { active: selectedFile?.id === file.id }]"
              @click="selectFile(file)"
            >
              <div class="file-info">
                <el-icon><Document /></el-icon>
                <span class="file-name">{{ file.fileName }}</span>
              </div>
              <div class="file-meta">
                <div class="file-tags">
                  <el-tag :type="getStatusType(file.status)" size="small">
                    {{ getStatusText(file.status) }}
                  </el-tag>
                  <el-tag :type="getAccessTypeTagType(file.accessType)" size="small">
                    {{ getAccessTypeText(file.accessType) }}
                  </el-tag>
                </div>
                <div class="file-actions">
                  <el-button
                    :icon="Setting"
                    size="small"
                    text
                    @click.stop="handleSetPermission(file)"
                    title="设置权限"
                  />
                  <el-button
                    type="danger"
                    :icon="Delete"
                    size="small"
                    text
                    @click.stop="handleDeleteFile(file.id)"
                    title="删除"
                  />
                </div>
              </div>
            </div>
          </div>
        </el-scrollbar>
      </el-aside>

      <!-- 主内容区：对话窗口 -->
      <el-main class="main-content">
        <div v-if="!selectedFile" class="empty-state">
          <el-empty description="请选择一个文档开始对话" />
        </div>
        <div v-else class="chat-container">
          <div class="chat-header">
            <h3>{{ selectedFile.fileName }}</h3>
            <el-button :icon="Refresh" text @click="loadChatHistory">刷新</el-button>
          </div>
          <el-scrollbar ref="chatScrollbar" height="calc(100vh - 200px)" class="chat-messages">
            <div v-for="message in chatHistory" :key="message.id" class="message-item">
              <div class="message-user">
                <el-avatar :icon="User" />
                <div class="message-content">
                  <div class="message-text">{{ message.question }}</div>
                  <div class="message-time">{{ formatTime(message.createTime) }}</div>
                </div>
              </div>
              <div class="message-assistant">
                <el-avatar :icon="ChatDotRound" />
                <div class="message-content">
                  <div class="message-text">{{ message.answer }}</div>
                  <div class="message-time">{{ formatTime(message.createTime) }}</div>
                  <el-tag v-if="message.isGeneralAnswer === 0" type="info" size="small" style="margin-top: 5px">
                    基于文档内容
                  </el-tag>
                </div>
              </div>
            </div>
          </el-scrollbar>
          <div class="chat-input">
            <el-input
              v-model="question"
              type="textarea"
              :rows="3"
              placeholder="请输入您的问题..."
              @keydown.ctrl.enter="handleSend"
            />
            <div class="input-actions">
              <span class="hint">Ctrl + Enter 发送</span>
              <el-button type="primary" @click="handleSend" :loading="sending">
                发送
              </el-button>
            </div>
          </div>
        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Upload,
  Delete,
  Document,
  Refresh,
  User,
  ChatDotRound,
  Setting
} from '@element-plus/icons-vue'
import { getFileList, deleteFile, uploadFile, setFilePermission } from '../api/file'
import { askQuestion, getChatHistory } from '../api/chat'
import request from '../api/request'

const fileList = ref([])
const selectedFile = ref(null)
const chatHistory = ref([])
const question = ref('')
const sending = ref(false)
const uploading = ref(false)
const chatScrollbar = ref(null)

const uploadAction = '/api/file/upload'
const uploadHeaders = {
  Authorization: `Bearer ${localStorage.getItem('token')}`
}

onMounted(() => {
  loadFileList()
})

const loadFileList = async () => {
  try {
    const res = await getFileList()
    fileList.value = res.data
  } catch (error) {
    ElMessage.error('加载文件列表失败')
  }
}

const selectFile = async (file) => {
  selectedFile.value = file
  await loadChatHistory()
  if (file.status === 2) {
    // 文档解析完成，可以开始对话
  } else if (file.status === 1) {
    ElMessage.info('文档正在解析中，请稍候...')
  } else if (file.status === 3) {
    ElMessage.error('文档解析失败，请重新上传')
  }
}

const loadChatHistory = async () => {
  if (!selectedFile.value) return
  try {
    const res = await getChatHistory(selectedFile.value.id)
    chatHistory.value = res.data
    nextTick(() => {
      scrollToBottom()
    })
  } catch (error) {
    ElMessage.error('加载对话历史失败')
  }
}

const handleSend = async () => {
  if (!question.value.trim()) {
    ElMessage.warning('请输入问题')
    return
  }
  if (!selectedFile.value) {
    ElMessage.warning('请先选择文档')
    return
  }
  if (selectedFile.value.status !== 2) {
    ElMessage.warning('文档尚未解析完成，无法提问')
    return
  }

  sending.value = true
  try {
    const res = await askQuestion(selectedFile.value.id, question.value)
    chatHistory.value.push(res.data)
    question.value = ''
    nextTick(() => {
      scrollToBottom()
    })
  } catch (error) {
    ElMessage.error('发送失败：' + (error.message || '网络错误'))
  } finally {
    sending.value = false
  }
}

const handleDeleteFile = async (fileId) => {
  try {
    await ElMessageBox.confirm('确定要删除这个文档吗？', '提示', {
      type: 'warning'
    })
    await deleteFile(fileId)
    ElMessage.success('删除成功')
    if (selectedFile.value?.id === fileId) {
      selectedFile.value = null
      chatHistory.value = []
    }
    loadFileList()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

const beforeUpload = (file) => {
  const allowedTypes = ['doc', 'docx', 'txt', 'ppt', 'pptx', 'pdf']
  const fileType = file.name.split('.').pop().toLowerCase()
  if (!allowedTypes.includes(fileType)) {
    ElMessage.error('不支持的文件格式，仅支持：doc, docx, txt, ppt, pptx, pdf')
    return false
  }
  uploading.value = true
  return true
}

const handleUploadSuccess = (response) => {
  uploading.value = false
  ElMessage.success('上传成功，文档正在解析中...')
  loadFileList()
  // 触发文档解析
  if (response.data) {
    request.post(`/document/parse/${response.data.id}`, null, {
      params: {
        fileType: response.data.fileType,
        objectName: response.data.minioObject
      }
    }).catch(() => {
      // 解析触发失败，不影响上传成功提示
    })
  }
}

const handleUploadError = () => {
  uploading.value = false
  ElMessage.error('上传失败')
}

const getStatusType = (status) => {
  const types = { 0: 'info', 1: 'warning', 2: 'success', 3: 'danger' }
  return types[status] || 'info'
}

const getStatusText = (status) => {
  const texts = { 0: '上传中', 1: '解析中', 2: '已完成', 3: '失败' }
  return texts[status] || '未知'
}

const formatTime = (time) => {
  if (!time) return ''
  const date = new Date(time)
  return date.toLocaleString('zh-CN')
}

const scrollToBottom = () => {
  if (chatScrollbar.value) {
    const scrollbar = chatScrollbar.value.wrapRef
    if (scrollbar) {
      scrollbar.scrollTop = scrollbar.scrollHeight
    }
  }
}

const handleSetPermission = async (file) => {
  const currentAccessType = file.accessType || 'private'
  const currentAllowedRoles = file.allowedRoles ? JSON.parse(file.allowedRoles) : []
  
  const accessTypeOptions = [
    { label: '私有（仅自己）', value: 'private' },
    { label: '公开（所有人）', value: 'public' },
    { label: '角色限制', value: 'role' }
  ]
  
  const roleOptions = [
    { label: '管理员', value: 'admin' },
    { label: '普通用户', value: 'user' }
  ]
  
  try {
    const { value: accessType } = await ElMessageBox({
      title: '设置访问类型',
      message: '请选择文档的访问类型',
      type: 'info',
      showCancelButton: true,
      confirmButtonText: '下一步',
      cancelButtonText: '取消',
      distinguishCancelAndClose: true,
      options: accessTypeOptions.map(opt => ({
        label: opt.label,
        value: opt.value,
        selected: opt.value === currentAccessType
      }))
    })
    
    let allowedRoles = []
    if (accessType === 'role') {
      const { value: roles } = await ElMessageBox({
        title: '选择允许的角色',
        message: '请选择可以访问此文档的角色（可多选）',
        type: 'info',
        showCancelButton: true,
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        options: roleOptions.map(opt => ({
          label: opt.label,
          value: opt.value,
          selected: currentAllowedRoles.includes(opt.value)
        }))
      })
      allowedRoles = Array.isArray(roles) ? roles : [roles]
    }
    
    await setFilePermission(file.id, accessType, allowedRoles)
    ElMessage.success('权限设置成功')
    loadFileList()
  } catch (error) {
    if (error !== 'cancel') {
      // 使用简单的选择方式
      const accessType = await new Promise((resolve, reject) => {
        ElMessageBox.confirm(
          '请选择访问类型：\n1. 私有（仅自己）\n2. 公开（所有人）\n3. 角色限制',
          '设置文档权限',
          {
            confirmButtonText: '私有',
            cancelButtonText: '取消',
            distinguishCancelAndClose: true,
            type: 'info'
          }
        ).then(() => {
          ElMessageBox.confirm('确定设置为私有？', '确认', {
            confirmButtonText: '确定',
            cancelButtonText: '取消'
          }).then(() => resolve('private')).catch(() => reject('cancel'))
        }).catch(() => {
          ElMessageBox.confirm('请选择：\n1. 公开\n2. 角色限制', '选择访问类型', {
            confirmButtonText: '公开',
            cancelButtonText: '角色限制'
          }).then(() => resolve('public')).catch(() => {
            // 角色限制需要选择角色
            ElMessageBox.confirm('请选择允许的角色（可多选）', '选择角色', {
              confirmButtonText: '管理员',
              cancelButtonText: '普通用户',
              distinguishCancelAndClose: true
            }).then(() => {
              resolve({ accessType: 'role', allowedRoles: ['admin'] })
            }).catch(() => {
              resolve({ accessType: 'role', allowedRoles: ['user'] })
            })
          })
        })
      })
      
      if (accessType !== 'cancel') {
        if (typeof accessType === 'object') {
          await setFilePermission(file.id, accessType.accessType, accessType.allowedRoles)
        } else {
          await setFilePermission(file.id, accessType, [])
        }
        ElMessage.success('权限设置成功')
        loadFileList()
      }
    }
  }
}

const getAccessTypeText = (accessType) => {
  const types = {
    'private': '私有',
    'public': '公开',
    'role': '角色限制'
  }
  return types[accessType] || '私有'
}

const getAccessTypeTagType = (accessType) => {
  const types = {
    'private': 'info',
    'public': 'success',
    'role': 'warning'
  }
  return types[accessType] || 'info'
}
</script>

<style scoped>
.home-container {
  height: 100vh;
  overflow: hidden;
}

.sidebar {
  border-right: 1px solid #e4e7ed;
  background: #f5f7fa;
}

.sidebar-header {
  padding: 20px;
  border-bottom: 1px solid #e4e7ed;
  background: white;
}

.sidebar-header h3 {
  margin-bottom: 15px;
  color: #303133;
}

.file-list {
  padding: 10px;
}

.file-item {
  padding: 15px;
  margin-bottom: 10px;
  background: white;
  border-radius: 5px;
  cursor: pointer;
  transition: all 0.3s;
}

.file-item:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.file-item.active {
  border: 2px solid #409eff;
}

.file-info {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.file-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.file-tags {
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
}

.file-actions {
  display: flex;
  gap: 5px;
}

.main-content {
  padding: 0;
  background: white;
}

.empty-state {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
}

.chat-container {
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.chat-header {
  padding: 20px;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chat-messages {
  padding: 20px;
  flex: 1;
}

.message-item {
  margin-bottom: 20px;
}

.message-user,
.message-assistant {
  display: flex;
  gap: 10px;
  margin-bottom: 10px;
}

.message-user {
  flex-direction: row;
}

.message-assistant {
  flex-direction: row-reverse;
}

.message-content {
  flex: 1;
  max-width: 70%;
}

.message-text {
  padding: 10px 15px;
  border-radius: 8px;
  word-wrap: break-word;
}

.message-user .message-text {
  background: #409eff;
  color: white;
}

.message-assistant .message-text {
  background: #f0f2f5;
  color: #303133;
}

.message-time {
  font-size: 12px;
  color: #909399;
  margin-top: 5px;
}

.message-user .message-time {
  text-align: left;
}

.message-assistant .message-time {
  text-align: right;
}

.chat-input {
  padding: 20px;
  border-top: 1px solid #e4e7ed;
}

.input-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
}

.hint {
  font-size: 12px;
  color: #909399;
}
</style>

