/**
 * 扁平化对象工具函数
 * 将嵌套对象转换为点分隔的键名结构
 */

import { unref, type Ref } from 'vue';

type NestedRecord = Record<string, unknown>;
type FlattenedValue = unknown;
type FlattenedRecord = Record<string, FlattenedValue>;
type NestedArray = unknown[];
type NestedContainer = NestedRecord | NestedArray;

function isRecordIndexable(value: unknown): value is NestedRecord {
  return isPlainObject(value);
}

function isPlainObject(value: unknown): value is NestedRecord {
  return (
    typeof value === 'object' &&
    value !== null &&
    !Array.isArray(value) &&
    !(value instanceof Date) &&
    !(value instanceof RegExp)
  );
}

/**
 * 扁平化对象，将嵌套对象转换为点分隔的键名
 * @param obj 要扁平化的对象
 * @param options 扁平化选项
 * @returns 扁平化后的对象
 */
export function flattenObject(
  obj: NestedRecord | Ref<NestedRecord>,
  options?: {
    prefix?: string;
    separator?: string;
    maxDepth?: number;
    shouldFlattenArrays?: boolean;
  },
): FlattenedRecord {
  const {
    prefix = '',
    separator = '.',
    maxDepth = Infinity,
    shouldFlattenArrays = false,
  } = options || {};
  const source = unref(obj);
  const flattened: FlattenedRecord = {};
  const currentDepth = prefix ? prefix.split(separator).length : 0;

  // 检查深度限制
  if (currentDepth >= maxDepth) {
    flattened[prefix] = source;
    return flattened;
  }
  if (Object.keys(source).length === 0) return {};
  for (const key in source) {
    if (Object.prototype.hasOwnProperty.call(source, key)) {
      const newKey = prefix ? `${prefix}${separator}${key}` : key;
      const value = source[key];

      if (value === null || value === undefined) {
        flattened[newKey] = value;
      } else if (isPlainObject(value)) {
        // 递归处理嵌套对象
        Object.assign(
          flattened,
          flattenObject(value, {
            prefix: newKey,
            separator,
            maxDepth,
            shouldFlattenArrays,
          }),
        );
      } else if (shouldFlattenArrays && Array.isArray(value)) {
        // 可选：处理数组
        for (let i = 0; i < value.length; i++) {
          const item = value[i];
          if (item === null || item === undefined) {
            flattened[`${newKey}${separator}${i}`] = item;
          } else if (isPlainObject(item)) {
            // 如果数组项是对象，则递归处理
            Object.assign(
              flattened,
              flattenObject(item, {
                prefix: `${newKey}${separator}${i}`,
                separator,
                maxDepth,
                shouldFlattenArrays,
              }),
            );
          } else {
            // 基础类型直接赋值
            flattened[`${newKey}${separator}${i}`] = item;
          }
        }
      } else {
        flattened[newKey] = value;
      }
    }
  }

  return flattened;
}

/**
 * 反扁平化对象，将点分隔的键名转换回嵌套对象
 * @param obj 扁平化的对象
 * @param options 反扁平化选项
 * @returns 嵌套对象
 */
export function unflattenObject(
  obj: NestedRecord | Ref<NestedRecord>,
  options?: {
    separator?: string;
    parseArrayIndices?: boolean;
  },
): NestedRecord {
  const { separator = '.', parseArrayIndices = false } = options || {};
  const result: NestedRecord = {};
  const source = unref(obj);

  for (const [key, value] of Object.entries(source)) {
    const keys = key.split(separator);
    let current: NestedContainer = result;

    for (let i = 0; i < keys.length - 1; i++) {
      const subKey = keys[i];
      const nextKey = keys[i + 1];
      const nextShouldBeArray = parseArrayIndices && /^\d+$/.test(nextKey);

      if (Array.isArray(current)) {
        const index = Number.parseInt(subKey, 10);
        const currentValue = current[index];
        if (
          currentValue === undefined ||
          (!Array.isArray(currentValue) && !isPlainObject(currentValue))
        ) {
          current[index] = nextShouldBeArray ? [] : {};
        }
        const nextValue: unknown = current[index];
        current = Array.isArray(nextValue)
          ? nextValue
          : isRecordIndexable(nextValue)
            ? nextValue
            : {};
        continue;
      }

      const currentValue = current[subKey];
      if (
        currentValue === undefined ||
        (!Array.isArray(currentValue) && !isPlainObject(currentValue))
      ) {
        current[subKey] = nextShouldBeArray ? [] : {};
      }
      const nextValue: unknown = current[subKey];
      current = Array.isArray(nextValue)
        ? nextValue
        : isRecordIndexable(nextValue)
          ? nextValue
          : {};
    }

    const finalKey = keys[keys.length - 1];

    // 处理数组索引（如果启用）
    if (parseArrayIndices && /^\d+$/.test(finalKey) && Array.isArray(current)) {
      current[Number.parseInt(finalKey, 10)] = value;
    } else if (Array.isArray(current) && /^\d+$/.test(finalKey)) {
      current[Number.parseInt(finalKey, 10)] = value;
    } else {
      (current as NestedRecord)[finalKey] = value;
    }
  }

  return result;
}

/**
 * 检查对象是否为扁平化格式
 * @param obj 要检查的对象
 * @param separator 分隔符
 * @returns 是否为扁平化格式
 */
export function isFlattened(
  obj: NestedRecord,
  separator: string = '.',
): boolean {
  return Object.keys(obj).some((key) => key.includes(separator));
}

/**
 * 获取对象的嵌套深度
 * @param obj 要检查的对象
 * @returns 嵌套深度
 */
export function getObjectDepth(obj: unknown): number {
  if (
    typeof obj !== 'object' ||
    obj === null ||
    obj instanceof Date ||
    obj instanceof RegExp
  ) {
    return 0;
  }

  if (Array.isArray(obj)) {
    return Math.max(0, ...obj.map((item) => getObjectDepth(item)));
  }

  if (!isPlainObject(obj)) {
    return 0;
  }

  const values = Object.values(obj);
  if (values.length === 0) {
    return 0;
  }

  return Math.max(0, ...values.map((value) => getObjectDepth(value))) + 1;
}
