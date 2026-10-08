import { ref } from 'vue'
import type { AgentUpdate, ChatStreamRequest, Citation } from '@/types/api'
import { authHeaders } from '@/api/http'

export interface ChatStreamHandlers {
  onStart?: (d: { conversationId: string; messageId: string }) => void
  onAgentUpdate?: (d: AgentUpdate) => void
  onToken?: (text: string) => void
  onCitation?: (c: Citation[]) => void
  onWarning?: (d: { type: string }) => void
  /** 后端质量校验未过、要重写这一版（D-1）：收到后必须丢弃已显示的正文，否则两版会拼在一起 */
  onRevision?: (d: { attempt: number; reason: string }) => void
  onDone?: (d: { conversationId: string; messageId: string; qualityPassed: boolean; attempts?: number }) => void
  onError?: (d: { message: string; retryable: boolean }) => void
}

interface SseFrame {
  event: string
  data: string
}

/**
 * 解析单帧文本（`event: x` + 多行 `data:`），返回 null 表示无有效 data。
 */
export function parseSseFrame(raw: string): SseFrame | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of raw.split('\n')) {
    if (!line || line.startsWith(':')) continue // 注释行/心跳
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      // spec：data: 后单空格不算内容；多行 data 用 \n 拼接
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }
  if (!dataLines.length) return null
  return { event, data: dataLines.join('\n') }
}

/**
 * SSE 客户端：fetch POST + ReadableStream。
 * - 跨 chunk 缓冲：按空行（\n\n）切帧，不完整帧留在缓冲区
 * - AbortController 中断；新请求自动打断旧流
 */
export function useChatStream() {
  const streaming = ref(false)
  let controller: AbortController | null = null

  function stop() {
    controller?.abort()
    controller = null
  }

  async function send(payload: ChatStreamRequest, handlers: ChatStreamHandlers) {
    // 再次发送 = 打断旧流
    stop()
    const ac = new AbortController()
    controller = ac
    streaming.value = true

    const dispatch = (frame: SseFrame) => {
      let data: unknown
      try {
        data = JSON.parse(frame.data)
      } catch {
        return // 非法 JSON 帧忽略
      }
      switch (frame.event) {
        case 'start': handlers.onStart?.(data as never); break
        case 'agent-update': handlers.onAgentUpdate?.(data as AgentUpdate); break
        case 'token': handlers.onToken?.((data as { text: string }).text); break
        case 'citation': handlers.onCitation?.(data as Citation[]); break
        case 'warning': handlers.onWarning?.(data as { type: string }); break
        case 'revision': handlers.onRevision?.(data as { attempt: number; reason: string }); break
        case 'done': handlers.onDone?.(data as never); break
        case 'error': handlers.onError?.(data as { message: string; retryable: boolean }); break
        default: break
      }
    }

    try {
      const res = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json', Accept: 'text/event-stream' }),
        body: JSON.stringify(payload),
        signal: ac.signal,
      })

      if (!res.ok || !res.body) {
        let message = `服务异常(${res.status})`
        let retryable = true
        try {
          const j = await res.json()
          message = j.message || message
          if (typeof j.retryable === 'boolean') retryable = j.retryable
        } catch { /* 非 JSON 错误体 */ }
        if (res.status === 401) message = '登录已过期，请重新登录'
        handlers.onError?.({ message, retryable })
        return
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buf = ''
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
        let idx: number
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          const frameText = buf.slice(0, idx)
          buf = buf.slice(idx + 2)
          const frame = parseSseFrame(frameText)
          if (frame) dispatch(frame)
        }
      }
      buf += decoder.decode()
      if (buf.trim()) {
        const frame = parseSseFrame(buf)
        if (frame) dispatch(frame)
      }
    } catch (e) {
      if ((e as Error).name === 'AbortError') {
        // 主动打断或被新请求替换：不算错误，由调用方收尾
        handlers.onError?.({ message: '__aborted__', retryable: false })
        return
      }
      handlers.onError?.({ message: '网络中断，连接已断开', retryable: true })
      throw e
    } finally {
      if (controller === ac) {
        controller = null
        streaming.value = false
      }
    }
  }

  return { streaming, send, stop }
}
