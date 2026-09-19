export type SnapshotAttachment = {
  attachmentId?: number;
  id?: number;
  fileName?: string;
  fileType?: string;
  uploaderName?: string;
  downloadUrl?: string;
};

export type SnapshotActivity = {
  activityId?: number;
  id?: number;
  activityTitle?: string;
  title?: string;
  activityType?: string;
  activityTime?: string;
  time?: string;
  activityContent?: string;
  content?: string;
  attachments?: SnapshotAttachment[];
};

export type SnapshotRecord = {
  type?: string;
  id?: number;
  activityId?: number;
  relatedActivityId?: number;
  activityIds?: number[];
  title?: string;
  activityTitle?: string;
  activityType?: string;
  taskType?: string;
  content?: string;
  activityContent?: string;
  taskContent?: string;
  time?: string;
  activityTime?: string;
  startTime?: string;
  endTime?: string;
  message?: string;
  attachments?: SnapshotAttachment[];
  relatedActivity?: SnapshotActivity;
};

export type AssistSnapshot = {
  version?: number;
  capturedAt?: string;
  modelName?: string;
  recordId?: number;
  related?: {
    opportunityId?: number;
    opportunityName?: string;
    companyId?: number;
    companyName?: string;
    contactId?: number;
    contactName?: string;
  };
  opportunity?: {
    opportunityId?: number;
    opportunityName?: string;
    stage?: string | number;
    stageName?: string;
    amount?: number | string;
    expectedCloseDate?: string;
    source?: string;
    description?: string;
    companyId?: number;
    companyName?: string;
    contactId?: number;
    contactName?: string;
    activities?: SnapshotActivity[];
  };
  record?: SnapshotRecord;
  deliveryAttachments?: SnapshotAttachment[];
};

export function normalizeAssistModelName(modelName?: string): string | undefined {
  return modelName?.trim().toLowerCase();
}

export function parseAssistSnapshot(snapshot?: string): AssistSnapshot | null {
  if (!snapshot?.trim()) return null;
  try {
    const parsed: unknown = JSON.parse(snapshot);
    if (!parsed || typeof parsed !== 'object') return null;
    const value = parsed as AssistSnapshot & {
      opportunityName?: string;
      stageName?: string;
      amount?: number | string;
      source?: string;
      description?: string;
      activities?: SnapshotActivity[];
    };
    // 兼容旧版审批快照：旧接口把商机字段直接放在根节点。
    if (!value.opportunity && (value.opportunityName || value.activities)) {
      value.opportunity = {
        opportunityName: value.opportunityName,
        stageName: value.stageName,
        amount: value.amount,
        source: value.source,
        description: value.description,
        activities: value.activities ?? [],
      };
    }
    return value;
  } catch {
    return null;
  }
}

function activityId(activity: SnapshotActivity): number | undefined {
  return activity.activityId ?? activity.id;
}

function normalizeRecordActivity(record?: SnapshotRecord): SnapshotActivity | null {
  if (!record || record.type !== 'businessActivity') return null;
  return {
    activityId: record.activityId ?? record.id,
    activityTitle: record.activityTitle ?? record.title,
    activityType: record.activityType,
    activityTime: record.activityTime ?? record.time,
    activityContent: record.activityContent ?? record.content,
    attachments: record.attachments ?? [],
  };
}

/** 按来源模型裁剪快照，避免活动/任务协助展示同商机的无关活动。 */
export function getScopedSnapshotActivities(
  snapshot: AssistSnapshot,
  modelName?: string,
): SnapshotActivity[] {
  modelName = normalizeAssistModelName(modelName ?? snapshot.modelName);
  const activities = snapshot.opportunity?.activities ?? [];
  if (modelName === 'sales_stage_approval') return activities;

  if (modelName === 'business_activity') {
    const targetId = snapshot.recordId ?? snapshot.record?.activityId ?? snapshot.record?.id;
    const matched = targetId == null ? [] : activities.filter((activity) => activityId(activity) === targetId);
    return matched.length ? matched : [normalizeRecordActivity(snapshot.record)].filter(
      (activity): activity is SnapshotActivity => activity !== null,
    );
  }

  if (modelName === 'contact_task') {
    const record = snapshot.record;
    const relatedIds = new Set<number>([
      ...(record?.activityIds ?? []),
      ...(record?.activityId == null ? [] : [record.activityId]),
      ...(record?.relatedActivityId == null ? [] : [record.relatedActivityId]),
    ]);
    const matched = activities.filter((activity) => {
      const id = activityId(activity);
      return id != null && relatedIds.has(id);
    });
    if (matched.length === 0 && record?.relatedActivity) return [record.relatedActivity];
    return matched;
  }

  return [];
}

export function getSnapshotRecord(snapshot: AssistSnapshot): SnapshotRecord | undefined {
  return snapshot.record;
}
