import { ref, onUnmounted, getCurrentInstance } from 'vue'

export function useSSE() {
  const isConnected = ref(false)
  const error = ref(null)
  const lastEventId = ref(null)

  let abortController = null
  let reader = null
  const listeners = new Map()

  // Only register lifecycle hook when inside a component setup context
  if (getCurrentInstance()) {
    onUnmounted(() => { disconnect() })
  }

  function on(eventType, callback) {
    if (!listeners.has(eventType)) {
      listeners.set(eventType, new Set())
    }
    listeners.get(eventType).add(callback)
    return () => listeners.get(eventType)?.delete(callback)
  }

  function emit(eventType, data) {
    const cbs = listeners.get(eventType)
    if (cbs) {
      cbs.forEach(cb => {
        try { cb(data) } catch (e) { console.warn('SSE listener error:', e) }
      })
    }
    const all = listeners.get('*')
    if (all) {
      all.forEach(cb => {
        try { cb(eventType, data) } catch (e) { console.warn('SSE listener error:', e) }
      })
    }
  }

  let reconnectAttempts = 0
  const MAX_RECONNECT = 5
  const BASE_DELAY = 1000

  function getReconnectDelay() {
    return Math.min(BASE_DELAY * Math.pow(2, reconnectAttempts), 30000)
  }

  async function connect(fetchFn, options = {}) {
    const { onDisconnect, reconnect = false } = options
    disconnect()

    abortController = new AbortController()
    isConnected.value = true
    error.value = null

    try {
      const response = await fetchFn(abortController.signal)
      console.log('[SSE] response status:', response.status, 'type:', response.headers.get('content-type'))

      if (!response.ok) {
        throw new Error(`SSE connection failed: ${response.status}`)
      }

      const contentType = response.headers.get('content-type') || ''
      if (contentType.includes('application/json')) {
        const json = await response.json()
          emit('done', json)
        return
      }

      reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) { console.log('[SSE] stream done'); break }

        buffer += decoder.decode(value, { stream: true })

        const parts = buffer.split('\n\n')
        buffer = parts.pop()

        for (const part of parts) {
          if (!part.trim()) continue
          const lines = part.split('\n')
          let eventType = 'message'
          let dataStr = ''

          for (const line of lines) {
            if (line.startsWith('event: ')) {
              eventType = line.slice(7).trim()
            } else if (line.startsWith('data: ')) {
              dataStr = line.slice(6)
            } else if (line.startsWith('id: ')) {
              lastEventId.value = line.slice(4).trim()
            }
          }

          if (dataStr) {
            try {
              const data = JSON.parse(dataStr)
              console.log('[SSE] evt:', eventType)
              emit(eventType, data)
            } catch {
              emit(eventType, dataStr)
            }
          }
        }
      }
    } catch (err) {
      console.log('[SSE] catch:', err.name, err.message)
      if (err.name === 'AbortError') {
        reconnectAttempts = 0
      } else {
        error.value = err.message
        emit('error', { message: err.message })
        if (onDisconnect) onDisconnect(err)

        if (reconnect && reconnectAttempts < MAX_RECONNECT) {
          reconnectAttempts++
          const delay = getReconnectDelay()
          await new Promise(resolve => setTimeout(resolve, delay))
          emit('reconnecting', { attempt: reconnectAttempts, delay })
          connect(fetchFn, options)
          return
        }
        reconnectAttempts = 0
      }
    } finally {
      console.log('[SSE] finally, isConnected:', isConnected.value)
      isConnected.value = false
      reader = null
    }
  }

  function disconnect() {
    if (abortController) {
      abortController.abort()
      abortController = null
    }
    isConnected.value = false
  }

  return { isConnected, error, lastEventId, on, emit, connect, disconnect }
}
