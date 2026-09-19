import { API_BASE_URL } from '@/api/config';
import { axiosInstance } from '@/api/apiClient';

export function resolveAttachmentDownloadUrl(downloadUrl?: string) {
  if (!downloadUrl) {
    return undefined;
  }

  if (
    downloadUrl.startsWith('http://') ||
    downloadUrl.startsWith('https://') ||
    downloadUrl.startsWith('blob:')
  ) {
    return downloadUrl;
  }

  const normalizedPath = downloadUrl.startsWith('/')
    ? downloadUrl
    : `/${downloadUrl}`;

  return `${API_BASE_URL}${normalizedPath}`;
}

function getDownloadFileName(disposition?: string, fallbackName?: string): string {
  if (disposition) {
    const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
    if (utf8Match) {
      try {
        return decodeURIComponent(utf8Match[1]);
      } catch {
        // 忽略解码失败，继续尝试其他方式
      }
    }
    const plainMatch = /filename="?([^";]+)"?/i.exec(disposition);
    if (plainMatch) {
      return plainMatch[1];
    }
  }
  return fallbackName || 'attachment';
}

/**
 * 通过 axios 下载附件（自动携带 token 请求头），
 * 避免新标签页直接打开下载链接导致后端拿不到 JWT。
 */
export async function downloadAttachmentByUrl(
  downloadUrl?: string,
  fallbackName = 'attachment',
): Promise<void> {
  const url = resolveAttachmentDownloadUrl(downloadUrl);
  if (!url) {
    return;
  }

  // API_BASE_URL 可能是相对路径（如 /api），用当前页面 origin 补全后再解析
  const token = new URL(url, window.location.origin).searchParams.get('token');
  if (!token) {
    window.open(url, '_blank');
    return;
  }

  const res = await axiosInstance.get('/public/attachment/download', {
    params: { token },
    responseType: 'blob',
  });
  const blob = res.data as Blob;
  const fileName = getDownloadFileName(
    res.headers['content-disposition'] as string | undefined,
    fallbackName,
  );

  const objectUrl = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = objectUrl;
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(objectUrl);
}
