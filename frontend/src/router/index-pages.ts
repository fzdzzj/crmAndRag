import { createRouter, createWebHashHistory } from 'vue-router';
import { routes } from 'vue-router/auto-routes';
import { TokenManager } from '../utils/token';

const router = createRouter({
  history: createWebHashHistory(),
  routes,
});

const publicRoutes = ['/login', '/dev/tester'];
router.beforeEach((to, from, next) => {
  if (publicRoutes.includes(to.path)) {
    next();
    return;
  }
  const token = TokenManager.getAccessToken();
  if (!token) {
    return next(`/login`);
  }
  if (TokenManager.isTokenExpired(token)) {
    return next(`/login`);
  }
  return next();
});

export default router;
