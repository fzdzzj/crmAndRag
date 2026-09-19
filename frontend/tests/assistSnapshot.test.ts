import { describe, expect, it } from 'vitest';
import { getScopedSnapshotActivities, parseAssistSnapshot } from '../src/components/assist/assistSnapshot';

const snapshot = {
  recordId: 12,
  opportunity: {
    opportunityName: '商机 A',
    activities: [
      { activityId: 12, activityTitle: '活动 A' },
      { activityId: 13, activityTitle: '活动 B' },
    ],
  },
  record: {
    type: 'businessActivity',
    title: '活动 A',
    attachments: [{ attachmentId: 1, fileName: 'a.pdf' }],
  },
};

describe('assist snapshot scope', () => {
  it('parses valid JSON and rejects missing or invalid snapshots', () => {
    expect(parseAssistSnapshot(JSON.stringify(snapshot))?.recordId).toBe(12);
    expect(parseAssistSnapshot()).toBeNull();
    expect(parseAssistSnapshot('{invalid')).toBeNull();
  });

  it('keeps all activities only for approval assistance', () => {
    const parsed = parseAssistSnapshot(JSON.stringify(snapshot))!;
    expect(getScopedSnapshotActivities(parsed, 'SALES_STAGE_APPROVAL')).toHaveLength(2);
  });

  it('keeps only the assisted activity for business activity assistance', () => {
    const parsed = parseAssistSnapshot(JSON.stringify(snapshot))!;
    expect(getScopedSnapshotActivities(parsed, 'business_activity').map((item) => item.activityId))
      .toEqual([12]);
  });

  it('does not guess unrelated activities for a contact task', () => {
    const parsed = parseAssistSnapshot(JSON.stringify({
      ...snapshot,
      record: { type: 'contactTask' },
    }))!;
    expect(getScopedSnapshotActivities(parsed, 'contact_task')).toEqual([]);
  });

  it('keeps compatibility with the old root-level opportunity snapshot', () => {
    const parsed = parseAssistSnapshot(JSON.stringify({
      opportunityName: '旧商机',
      activities: [{ activityId: 1, activityTitle: '旧活动' }],
    }))!;
    expect(parsed.opportunity?.opportunityName).toBe('旧商机');
    expect(getScopedSnapshotActivities(parsed, 'sales_stage_approval')).toHaveLength(1);
  });
});
