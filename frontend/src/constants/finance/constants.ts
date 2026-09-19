/** 回款状态映射：text 为显示文案，color 为标签颜色 */
export const paymentStatusMap: Record<number, { text: string; color: string }> = {
  0: { text: '已确认', color: 'success' },
  1: { text: '待确认', color: 'warning' },
  2: { text: '已作废', color: 'error' },
};

export function getPaymentStatusInfo(status?: number, statusDesc?: string) {
  if (status !== undefined && paymentStatusMap[status]) {
    return paymentStatusMap[status];
  }
  return { text: statusDesc ?? '未知', color: 'default' };
}
