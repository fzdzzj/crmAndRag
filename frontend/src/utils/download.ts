export function downloadFromBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}
export function downloadFromBase64(base64: string, filename: string) {
  const binaryString = atob(base64);
  const bytes = new Uint8Array(binaryString.length);
  for (let i = 0; i < binaryString.length; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }
  const blob = new Blob([bytes], { type: 'application/vnd.ms-excel' });
  downloadFromBlob(blob, filename);
}
export function fileToBase64String(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('读取文件失败'));
    reader.onload = () => {
      if (typeof reader.result === 'string' || reader.result === null) {
        reject(new Error('读取文件失败：结果不是 ArrayBuffer'));
        return;
      }
      const buffer = reader.result;
      const bytes = new Uint8Array(buffer);
      let binary = '';
      for (let i = 0; i < bytes.length; i++)
        binary += String.fromCharCode(bytes[i]);
      resolve(btoa(binary));
    };
    reader.readAsArrayBuffer(file);
  });
}
