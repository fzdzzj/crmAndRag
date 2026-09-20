/**
 * SSE 流测试替身：产出真实 Response / ReadableStream，
 * 让被测代码走与浏览器一致的消费路径（resp.body.getReader()）。
 */
const encoder = new TextEncoder();

export function frame(event: string, data: unknown, id?: string): string {
  const lines: string[] = [];
  if (id) lines.push(`id:${id}`);
  lines.push(`event:${event}`);
  lines.push(`data:${typeof data === 'string' ? data : JSON.stringify(data)}`);
  return `${lines.join('\n')}\n\n`;
}

function responseWith(stream: ReadableStream<Uint8Array>, status = 200): Response {
  return new Response(stream, {
    status,
    headers: { 'Content-Type': 'text/event-stream' },
  });
}

/** 正常流：按 chunk 依次吐出 frames 后关闭 */
export function sseResponse(frames: string[]): Response {
  return responseWith(
    new ReadableStream<Uint8Array>({
      start(controller) {
        for (const f of frames) controller.enqueue(encoder.encode(f));
        controller.close();
      },
    }),
  );
}

/**
 * 网络抖动：deliver 前 keepCount 个 chunk 后以 TypeError('Failed to fetch') 中断。
 * 用 pull 而不是 start 里连发，保证读端一定先取到已入队 chunk 再看到错误。
 */
export function sseResponseThatDrops(frames: string[], keepCount: number): Response {
  const pending = [...frames];
  let delivered = 0;
  return responseWith(
    new ReadableStream<Uint8Array>({
      pull(controller) {
        if (delivered < keepCount && pending.length > 0) {
          controller.enqueue(encoder.encode(pending.shift() as string));
          delivered += 1;
          return;
        }
        controller.error(new TypeError('Failed to fetch'));
      },
    }),
  );
}

export function httpErrorResponse(status: number, body: string): Response {
  return new Response(body, {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
