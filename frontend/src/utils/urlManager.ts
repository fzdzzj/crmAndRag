import { flattenObject } from './flattenObject';

/**
 * URL 管理工具类
 * 用于无刷新更新 URL 和处理 URL 参数
 */
export class URLManager {
  /**
   * 更新 URL 参数而不刷新页面
   * @param searchParams URLSearchParams 对象
   */
  static updateURL(searchParams: URLSearchParams): void {
    const newUrl = `${window.location.pathname}?${searchParams.toString()}${window.location.hash}`;
    window.history.pushState(window.history.state, '', newUrl);
  }

  /**
   * 从对象更新 URL 参数
   * @param obj 包含搜索参数的对象
   * @param options ObjectTransferToURLSearchParams 的选项
   */
  static updateURLFromObject(
    obj: Record<string, unknown>,
    options?: {
      serializer?: (v: unknown) => string;
    },
  ): void {
    const searchParams = new URLSearchParams();
    const defaultSerializer = (v: unknown) => String(v);
    const serializer = options?.serializer ?? defaultSerializer;

    // 首先扁平化对象
    const flattenedObj = flattenObject(obj);

    for (const [key, value] of Object.entries(flattenedObj)) {
      if (value === undefined || value === null) {
        continue;
      }

      if (Array.isArray(value)) {
        value.forEach((item) => {
          if (item !== null && item !== undefined) {
            searchParams.append(key, serializer(item));
          }
        });
      } else {
        searchParams.append(key, serializer(value));
      }
    }

    this.updateURL(searchParams);
  }

  /**
   * 获取当前页面的 URLSearchParams
   * @returns URLSearchParams 对象
   */
  static getCurrentSearchParams(): URLSearchParams {
    return new URLSearchParams(window.location.search);
  }

  /**
   * 获取当前页面的完整路径（包含查询参数）
   * @returns 完整路径字符串
   */
  static getCurrentPath(): string {
    return window.location.pathname + window.location.search + window.location.hash;
  }

  /**
   * 监听浏览器前进后退事件
   * @param callback URL 变化时的回调函数
   * @returns 清理函数，调用后移除事件监听
   */
  static onPopState(callback: (event: PopStateEvent) => void): () => void {
    window.addEventListener('popstate', callback);

    return () => {
      window.removeEventListener('popstate', callback);
    };
  }

  /**
   * 替换当前历史记录项（不会在历史记录中留下记录）
   * @param searchParams URLSearchParams 对象
   */
  static replaceURL(searchParams: URLSearchParams): void {
    const newUrl = `${window.location.pathname}?${searchParams.toString()}${window.location.hash}`;
    window.history.replaceState(window.history.state, '', newUrl);
  }

  /**
   * 从对象替换当前 URL
   * @param obj 包含搜索参数的对象
   * @param options ObjectTransferToURLSearchParams 的选项
   */
  static replaceURLFromObject(
    obj: Record<string, unknown>,
    options?: {
      serializer?: (v: unknown) => string;
    },
  ): void {
    const searchParams = new URLSearchParams();
    const defaultSerializer = (v: unknown) => String(v);
    const serializer = options?.serializer ?? defaultSerializer;

    // 首先扁平化对象
    const flattenedObj = flattenObject(obj);

    for (const [key, value] of Object.entries(flattenedObj)) {
      if (value === undefined || value === null) {
        continue;
      }

      if (Array.isArray(value)) {
        value.forEach((item) => {
          if (item !== null && item !== undefined) {
            searchParams.append(key, serializer(item));
          }
        });
      } else {
        searchParams.append(key, serializer(value));
      }
    }

    this.replaceURL(searchParams);
  }

  /**
   * 清空所有 URL 参数
   */
  static clearSearchParams(): void {
    const newUrl = window.location.pathname + window.location.hash;
    window.history.pushState(window.history.state, '', newUrl);
  }
}
