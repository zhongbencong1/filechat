import request from './request'

export const askQuestion = (documentId, question) => {
  return request.post('/chat/ask', {
    documentId,
    question
  })
}

export const getChatHistory = (documentId) => {
  return request.get('/chat/history', {
    params: { documentId }
  })
}

