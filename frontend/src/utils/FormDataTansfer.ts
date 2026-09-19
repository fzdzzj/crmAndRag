interface URLSearchParamsTransferToObjectOptions {
  loader: (v: string) => unknown;
}

interface ObjectTransferToURLSearchParamsOptions {
  serializer: (v: unknown) => string;
}

export class FormTransfer {
  static URLSearchParamsTransferToObject(
    formData: URLSearchParams,
    options?: URLSearchParamsTransferToObjectOptions,
  ): Record<string, unknown> {
    const result: Record<string, unknown> = {};
    const loader = options?.loader ?? ((v) => v);
    for (const [key, value] of formData.entries()) {
      if (key in result) {
        const existing = result[key];
        if (Array.isArray(existing)) {
          existing.push(loader(value));
        } else {
          result[key] = [existing, loader(value)];
        }
      } else {
        result[key] = loader(value);
      }
    }

    return result;
  }

  static ObjectTransferToURLSearchParams<K extends string, V>(
    obj: Record<K, V>,
    options?: ObjectTransferToURLSearchParamsOptions,
  ): URLSearchParams {
    const formData = new URLSearchParams();
    const serializer = options?.serializer ?? ((v) => String(v));

    for (const [key, value] of Object.entries(obj)) {
      if (value === undefined || value === null) {
        continue;
      }

      if (Array.isArray(value)) {
        value.forEach((item) => {
          if (item !== null && item !== undefined) {
            formData.append(key, serializer(item));
          }
        });
      } else {
        formData.append(key, serializer(value));
      }
    }

    return formData;
  }

  static ObjectTransferToMap<K extends string, V>(
    obj: Record<K, V>,
  ): Map<K, V> {
    const map = new Map<K, V>();

    for (const [key, value] of Object.entries(obj) as [K, V][]) {
      if (value !== undefined) {
        map.set(key, value);
      }
    }

    return map;
  }

  static MapTransferObject<K extends string, V>(map: Map<K, V>): Record<K, V> {
    const obj = {} as Record<K, V>;

    for (const [key, value] of map.entries()) {
      if (value !== undefined) {
        obj[key] = value;
      }
    }

    return obj;
  }
}
