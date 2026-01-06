import request from './request'

export const login = (username, password) => {
  return request.post('/auth/login', { username, password })
}

export const register = (username, password, email) => {
  return request.post('/auth/register', { username, password, email })
}

