import { Modal } from 'ant-design-vue';
import {
  useSoftDeleteContact,
  useHardDeleteContact,
  useRestoreContact,
} from '@/hooks/useContact.ts';

function ensureArray(ids: number | number[]): number[] {
  return Array.isArray(ids) ? ids : [ids];
}

type DoneOption = { onDone?: () => void };

export function useContactActions(refetch?: () => void | Promise<unknown>) {
  const { mutate: softDelete } = useSoftDeleteContact();
  const { mutate: hardDelete } = useHardDeleteContact();
  const { mutate: restore } = useRestoreContact();

  function confirmSoftDelete(ids: number | number[], opts?: DoneOption) {
    Modal.confirm({
      title: '确认将所选联系人移入回收站？',
      onOk() {
        softDelete(ensureArray(ids), {
          onSuccess: () => {
            opts?.onDone?.();
            void refetch?.();
          },
        });
      },
    });
  }

  function confirmHardDelete(ids: number | number[], opts?: DoneOption) {
    Modal.confirm({
      title: '确认批量硬删除所选联系人？此操作不可恢复',
      okType: 'danger',
      onOk() {
        hardDelete(ensureArray(ids), {
          onSuccess: () => {
            opts?.onDone?.();
            void refetch?.();
          },
        });
      },
    });
  }

  function confirmRestore(ids: number | number[], opts?: DoneOption) {
    Modal.confirm({
      title: '确认批量恢复这些联系人？',
      onOk() {
        restore(ensureArray(ids), {
          onSuccess: () => {
            opts?.onDone?.();
            void refetch?.();
          },
        });
      },
    });
  }

  return {
    confirmSoftDelete,
    confirmHardDelete,
    confirmRestore,
  };
}
