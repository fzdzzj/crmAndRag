import { message } from 'ant-design-vue';

type FormLike = { validate: () => Promise<unknown> };

/**
 * 统一表单校验入口。
 * - 校验失败：AntD 已在表单项下内联展示错误，这里静默返回 false；
 * - 非校验异常（自定义 validator 等真实 bug）：不吞掉，打印并提示。
 */
export async function validateForm(formRef?: FormLike | null): Promise<boolean> {
  if (!formRef) {
    return true;
  }
  try {
    await formRef.validate();
    return true;
  } catch (error) {
    if (error && typeof error === 'object' && 'errorFields' in error) {
      return false;
    }
    console.error('表单校验异常:', error);
    message.error('表单校验异常，请稍后重试');
    return false;
  }
}
