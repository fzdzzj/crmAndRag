const ESCAPE_HTML_MAP: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
};

export function formatTutorialContent(content: string | string[]) {
  if (typeof content === 'string') {
    return escapeHtml(content);
  }

  return content
    .map(
      (item) =>
        `<div class="tutorial-content-item">${escapeHtml(item)}</div>`,
    )
    .join('');
}

function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, (char) => ESCAPE_HTML_MAP[char]);
}
