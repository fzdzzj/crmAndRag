/**
 * 判断当前环境是开发环境还是生产环境
 * 在Vite中，import.meta.env.DEV为true表示开发环境，import.meta.env.PROD为true表示生产环境
 */

/**
 * 获取当前环境类型
 * @returns {'development' | 'production'} 当前环境类型
 */
export const getEnvironment = (): 'development' | 'production' => {
  return import.meta.env.DEV ? 'development' : 'production';
};

/**
 * 检查是否为开发环境
 * @returns {boolean} 是否为开发环境
 */
export const isDevelopment = (): boolean => {
  return import.meta.env.DEV;
};

/**
 * 检查是否为生产环境
 * @returns {boolean} 是否为生产环境
 */
export const isProduction = (): boolean => {
  return import.meta.env.PROD;
};
