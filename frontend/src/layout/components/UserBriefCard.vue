<template>
  <div
    class="user-card"
    @mouseenter="handleMouseEnter"
    @mouseleave="handleMouseLeave"
  >
    <a-avatar
      class="avatar"
      :size="40"
      :style="{ backgroundColor: '#3176ff', fontSize: '18px' }"
    >
      {{ currentUser?.realName?.charAt(0)?.toUpperCase() }}
    </a-avatar>
    <div class="info">
      <h4 class="username">{{ currentUser?.realName }}</h4>
      <h5 class="role-name">{{ currentUserRole?.roleName }}</h5>
    </div>

    <transition name="fade-slide">
      <div v-if="isLogoutMuneShow" class="user-menu-popover">
        <div class="user-info-brief">
          <div class="brief-header">
            <a-avatar
              :size="56"
              :style="{
                backgroundColor: '#3176ff',
                fontSize: '24px',
                marginBottom: '8px',
              }"
            >
              {{ currentUser?.realName?.charAt(0)?.toUpperCase() }}
            </a-avatar>
            <div class="brief-name">{{ currentUser?.realName }}</div>
            <div class="brief-role">{{ currentUserRole?.roleName }}</div>
          </div>

          <div class="brief-details">
            <div class="detail-item">
              <PhoneOutlined class="icon" />
              <span class="value">{{ currentUser?.phone || '暂无电话' }}</span>
            </div>
            <div class="detail-item">
              <MailOutlined class="icon" />
              <span class="value">{{ currentUser?.email || '暂无邮箱' }}</span>
            </div>
          </div>
        </div>

        <div class="menu-actions">
          <div class="menu-item" @click.stop="handleEditProfile">
            <EditOutlined />
            <span>修改个人信息</span>
          </div>
          <div class="menu-item" @click.stop="handleChangePassword">
            <LockOutlined />
            <span>修改密码</span>
          </div>
          <div class="menu-divider"></div>
          <div class="menu-item danger" @click="() => handleLogout()">
            <LogoutOutlined />
            <span>退出登录</span>
          </div>
        </div>
      </div>
    </transition>
  </div>
  <ChangePasswordModal ref="changePasswordModalRef" />
  <UserProfileModal ref="userProfileModalRef" />
</template>
<script setup lang="ts">
import { ref } from 'vue';
import { useQuery } from '@tanstack/vue-query';
import { Avatar as AAvatar } from 'ant-design-vue';
import {
  PhoneOutlined,
  MailOutlined,
  EditOutlined,
  LockOutlined,
  LogoutOutlined,
} from '@ant-design/icons-vue';
import { getUserMy } from '@/api/axios';
import apiClient from '@/api/apiClient';
import { useLogout } from '../../hooks/useAuth';
import { useQueryCurrentUserRole } from '@/hooks/useUser';
import ChangePasswordModal from '@/components/user/ChangePasswordModal.vue';
import UserProfileModal from '@/components/user/UserProfileModal.vue';

const isLogoutMuneShow = ref(false);
const { mutate: handleLogout } = useLogout();
const { data: currentUserRole } = useQueryCurrentUserRole();
const { data: currentUser } = useQuery({
  queryKey: ['currentUser'],
  queryFn: () => getUserMy({ client: apiClient }),
    select: (res) => res.data?.data,
});

const changePasswordModalRef = ref<InstanceType<typeof ChangePasswordModal>>();
const userProfileModalRef = ref<InstanceType<typeof UserProfileModal>>();

const handleChangePassword = () => {
  changePasswordModalRef.value?.openModal();
  isLogoutMuneShow.value = false;
};

const handleEditProfile = () => {
  const profile = currentUser.value;
  if (profile) {
    userProfileModalRef.value?.open(profile);
  }
  isLogoutMuneShow.value = false;
};

const handleMouseEnter = () => {
  isLogoutMuneShow.value = true;
};

const handleMouseLeave = () => {
  isLogoutMuneShow.value = false;
};
</script>

<style scoped>
.user-card {
  width: var(--sider-width);
  height: 78px;
  border-top: 1px solid #f0f0f0;
  position: absolute;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 24px;
  cursor: pointer;
  transition: all 0.3s ease;
  background-color: #fff;
  z-index: 100;
}

.user-card:hover {
  background-color: #f9fafb;
}

.user-card .info {
  flex: 1;
  overflow: hidden;
}

.user-card .info .username {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-card .info .role-name {
  margin: 2px 0 0;
  font-size: 12px;
  color: #6b7280;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-card .user-menu-popover {
  position: absolute;
  bottom: 85px; /* Spacing from bottom */
  left: 12px; /* Slight offset */
  width: 240px; /* Wider menu */
  background-color: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
  overflow: hidden;
  transform-origin: bottom left;
  border: 1px solid #f0f0f0;
}

.user-card .user-menu-popover .user-info-brief {
  padding: 20px;
  background: linear-gradient(to bottom, #f9fafb, #fff);
  border-bottom: 1px solid #f0f0f0;
  text-align: center;
}

.user-card .user-menu-popover .user-info-brief .brief-header {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 16px;
}

.user-card .user-menu-popover .user-info-brief .brief-header .brief-name {
  font-size: 16px;
  font-weight: 600;
  color: #111827;
  margin-top: 8px;
}

.user-card .user-menu-popover .user-info-brief .brief-header .brief-role {
  font-size: 12px;
  color: #6b7280;
  background: #f3f4f6;
  padding: 2px 8px;
  border-radius: 10px;
  margin-top: 4px;
}

.user-card .user-menu-popover .user-info-brief .brief-details {
  text-align: left;
}

.user-card .user-menu-popover .user-info-brief .brief-details .detail-item {
  display: flex;
  align-items: center;
  font-size: 13px;
  color: #4b5563;
  margin-bottom: 8px;
}

.user-card
  .user-menu-popover
  .user-info-brief
  .brief-details
  .detail-item:last-child {
  margin-bottom: 0;
}

.user-card
  .user-menu-popover
  .user-info-brief
  .brief-details
  .detail-item
  .icon {
  margin-right: 10px;
  color: #9ca3af;
  font-size: 14px;
}

.user-card
  .user-menu-popover
  .user-info-brief
  .brief-details
  .detail-item
  .value {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-card .user-menu-popover .menu-actions {
  padding: 8px;
}

.user-card .user-menu-popover .menu-actions .menu-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  color: #374151;
  font-size: 14px;
  margin-bottom: 2px;
}

.user-card .user-menu-popover .menu-actions .menu-item .anticon {
  margin-right: 12px;
  font-size: 16px;
  color: #6b7280;
}

.user-card .user-menu-popover .menu-actions .menu-item:hover {
  background-color: #f3f4f6;
  color: #111827;
}

.user-card .user-menu-popover .menu-actions .menu-item:hover .anticon {
  color: #3176ff;
}

.user-card .user-menu-popover .menu-actions .menu-item.danger {
  color: #ef4444;
}

.user-card .user-menu-popover .menu-actions .menu-item.danger .anticon {
  color: #ef4444;
}

.user-card .user-menu-popover .menu-actions .menu-item.danger:hover {
  background-color: #fef2f2;
}

.user-card .user-menu-popover .menu-actions .menu-divider {
  height: 1px;
  background-color: #f3f4f6;
  margin: 4px 8px;
}

/* Transition Effects */
.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: all 0.3s cubic-bezier(0.16, 1, 0.3, 1);
}

.fade-slide-enter-from,
.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(10px) scale(0.95);
}
</style>
