// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { message } from 'ant-design-vue';

vi.mock('@/api/apiClient.ts', () => ({
  default: { name: 'mockApiClient' },
}));

const sdkState = vi.hoisted(() => ({
  postAiInsightCustomerByCompanyIdToAction: vi.fn(),
  getAiInsightCustomerByCompanyId: vi.fn(),
}));

vi.mock('@/api/axios/sdk.gen.ts', () => ({
  postAiInsightCustomerByCompanyIdToAction: (...args: unknown[]) =>
    sdkState.postAiInsightCustomerByCompanyIdToAction(...(args as [])),
  getAiInsightCustomerByCompanyId: (...args: unknown[]) =>
    sdkState.getAiInsightCustomerByCompanyId(...(args as [])),
}));

vi.mock('ant-design-vue', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    message: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  };
});

import {
  useCustomerInsightToAction,
  type InsightActionType,
} from '@/hooks/useCustomerInsightToAction';
import { useCustomerInsight } from '@/hooks/useCustomerInsight';

const toast = message as unknown as { success: vi.Mock; error: vi.Mock; warning: vi.Mock };

function makeActionResult(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    pendingId: 'PD-100',
    status: 'PENDING',
    actionType: 'CREATE_CONTACT_TASK',
    suggestion: 'suggest arranging a visit soon',
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('useCustomerInsightToAction', () => {
  it('default actionType: body without actionType, returns pendingId and success toast', async () => {
    sdkState.postAiInsightCustomerByCompanyIdToAction.mockResolvedValue({
      data: { code: 1, data: makeActionResult() },
    });
    const { convert } = useCustomerInsightToAction();
    const result = await convert({ companyId: 5, suggestionIndex: 0 });

    expect(sdkState.postAiInsightCustomerByCompanyIdToAction).toHaveBeenCalledWith(
      expect.objectContaining({
        client: expect.anything(),
        path: { companyId: 5 },
        body: { suggestionIndex: 0 },
      }),
    );
    expect(result?.pendingId).toBe('PD-100');
    expect(result?.actionType).toBe('CREATE_CONTACT_TASK');
    expect(toast.success).toHaveBeenCalled();
  });

  it('explicit actionType: passes through body, CREATE_BUSINESS_ACTIVITY for activity', async () => {
    sdkState.postAiInsightCustomerByCompanyIdToAction.mockResolvedValue({
      data: { code: 1, data: makeActionResult({ actionType: 'CREATE_BUSINESS_ACTIVITY' }) },
    });
    const { convert } = useCustomerInsightToAction();
    const type: InsightActionType = 'CREATE_BUSINESS_ACTIVITY';
    const result = await convert({ companyId: 9, suggestionIndex: 2, actionType: type });

    expect(sdkState.postAiInsightCustomerByCompanyIdToAction).toHaveBeenCalledWith(
      expect.objectContaining({
        path: { companyId: 9 },
        body: { suggestionIndex: 2, actionType: 'CREATE_BUSINESS_ACTIVITY' },
      }),
    );
    expect(result?.actionType).toBe('CREATE_BUSINESS_ACTIVITY');
  });

  it('failure/rejection: surfaces error message, returns null without throwing', async () => {
    sdkState.postAiInsightCustomerByCompanyIdToAction.mockRejectedValue(
      new Error('suggestionIndex out of range'),
    );
    const { convert, error } = useCustomerInsightToAction();
    const result = await convert({ companyId: 5, suggestionIndex: 99 });

    expect(result).toBeNull();
    expect(error.value).toBe('suggestionIndex out of range');
    expect(toast.error).toHaveBeenCalledWith('suggestionIndex out of range');
  });
});

describe('useCustomerInsight', () => {
  it('load insight success: data holds actionSuggestions and riskAlerts', async () => {
    sdkState.getAiInsightCustomerByCompanyId.mockResolvedValue({
      data: {
        code: 1,
        data: {
          companyId: 5,
          healthScore: 72,
          actionSuggestions: ['arrange a visit', 'check payment progress'],
          riskAlerts: ['overdue exists'],
        },
      },
    });
    const { load, data } = useCustomerInsight();
    const loaded = await load(5);

    expect(sdkState.getAiInsightCustomerByCompanyId).toHaveBeenCalledWith(
      expect.objectContaining({ client: expect.anything(), path: { companyId: 5 } }),
    );
    expect(loaded?.healthScore).toBe(72);
    expect(data.value?.actionSuggestions).toHaveLength(2);
    expect(data.value?.riskAlerts).toEqual(['overdue exists']);
  });

  it('load insight failure: error surfaced and data stays null', async () => {
    sdkState.getAiInsightCustomerByCompanyId.mockRejectedValue(new Error('company not found'));
    const { load, error, data } = useCustomerInsight();
    const result = await load(404);

    expect(result).toBeNull();
    expect(error.value).toBe('company not found');
    expect(data.value).toBeNull();
    expect(toast.error).toHaveBeenCalledWith('company not found');
  });
});
