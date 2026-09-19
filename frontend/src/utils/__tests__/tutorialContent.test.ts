import { describe, expect, it } from 'vitest';
import { formatTutorialContent } from '@/utils/tutorialContent';

describe('tutorialContent', () => {
  it('字符串内容会被转义', () => {
    expect(formatTutorialContent('<b>CRM & 教学</b>')).toBe(
      '&lt;b&gt;CRM &amp; 教学&lt;/b&gt;',
    );
  });

  it('数组内容按要点渲染为独立行', () => {
    expect(formatTutorialContent(['先点「新建」', '再点「保存」'])).toBe(
      '<div class="tutorial-content-item">先点「新建」</div><div class="tutorial-content-item">再点「保存」</div>',
    );
  });
});
