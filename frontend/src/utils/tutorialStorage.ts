import { TokenManager } from '@/utils/token';

const INTRO_SEEN_PREFIX = 'tour.intro.seen';
const DONE_PREFIX = 'tour.done.';
const RESUME_PREFIX = 'tour.resume.';

/** 使用教程的本地标记：按用户维度记录首次弹出、教程完成与中断续学状态 */
export class TutorialStorage {
  static hasSeenIntro() {
    return localStorage.getItem(TutorialStorage.getUserKey(INTRO_SEEN_PREFIX)) === '1';
  }
  static markIntroSeen() {
    localStorage.setItem(TutorialStorage.getUserKey(INTRO_SEEN_PREFIX), '1');
  }
  static isTutorialDone(tutorialId: string) {
    return localStorage.getItem(TutorialStorage.getUserKey(DONE_PREFIX + tutorialId)) === '1';
  }
  static markTutorialDone(tutorialId: string) {
    localStorage.setItem(TutorialStorage.getUserKey(DONE_PREFIX + tutorialId), '1');
  }
  static getTutorialResume(tutorialId: string): number | null {
    const raw = localStorage.getItem(TutorialStorage.getUserKey(RESUME_PREFIX + tutorialId));
    if (raw === null) {
      return null;
    }
    const parsed = Number.parseInt(raw, 10);
    return Number.isFinite(parsed) ? parsed : null;
  }
  static markTutorialResume(tutorialId: string, stepIndex: number) {
    localStorage.setItem(TutorialStorage.getUserKey(RESUME_PREFIX + tutorialId), String(stepIndex));
  }
  static clearTutorialResume(tutorialId: string) {
    localStorage.removeItem(TutorialStorage.getUserKey(RESUME_PREFIX + tutorialId));
  }
  /** 同一浏览器区分不同账号，避免标记互相污染 */
  private static getUserKey(suffix: string) {
    return `${suffix}.${TokenManager.getUserID() ?? 'anonymous'}`;
  }
}
