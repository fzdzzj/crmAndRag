export class TokenManager {
  static getUserID() {
    const payload = TokenManager.getPayload();
    if (!payload) {
      return null;
    }
    return payload.userID;
  }
  static getAccessToken() {
    return localStorage.getItem('token');
  }
  static setAccessToken(accessToken: string) {
    localStorage.setItem('token', accessToken);
  }
  static removeAccessToken() {
    localStorage.removeItem('token');
  }
  static isTokenExpired(token: string) {
    const payload = TokenManager.parseAccessToken(token);
    if (!payload) {
      return true;
    }
    return payload.exp < Date.now() / 1000;
  }
  static isTokenValid(token: string) {
    try {
      const payload = TokenManager.parseAccessToken(token);
      if (!payload) {
        return false;
      }
      return !TokenManager.isTokenExpired(token);
    } catch (_error) {
      return false;
    }
  }
  static parseAccessToken(token: string) {
    if (!token) {
      return null;
    }
    const payload = token.split('.')[1];
    if (!payload) {
      return null;
    }
    try {
      // 兼容 base64url → base64
      let base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
      const padding = base64.length % 4;
      if (padding === 2) base64 += '==';
      else if (padding === 3) base64 += '=';
      const json = atob(base64);
      return JSON.parse(json) as payload;
    } catch (_error) {
      return null;
    }
  }
  static setPayload(payload: payload | null) {
    localStorage.setItem('payload', JSON.stringify(payload));
  }
  static getPayload() {
    const payload = localStorage.getItem('payload');
    if (!payload) {
      return null;
    }
    return JSON.parse(payload) satisfies payload;
  }
  static removePayload() {
    localStorage.removeItem('payload');
  }
  static getRoleID() {
    const payload = TokenManager.getPayload();
    if (!payload) {
      return null;
    }
    return payload.roleID;
  }
}
export interface payload {
  roleID: number;
  userID: number;
  exp: number;
}
