import request from './request'

export const uploadFile = (file) => {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/file/upload', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  })
}

export const getFileList = () => {
  return request.get('/file/list')
}

export const getFileDetail = (documentId) => {
  return request.get(`/file/${documentId}`)
}

export const deleteFile = (documentId) => {
  return request.delete(`/file/${documentId}`)
}

export const setFilePermission = (documentId, accessType, allowedRoles) => {
  return request.put(`/file/${documentId}/permission`, {
    accessType,
    allowedRoles
  })
}

