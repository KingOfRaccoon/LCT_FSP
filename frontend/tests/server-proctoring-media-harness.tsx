import { useProctoringMedia } from '../src/features/proctoring/useProctoringMedia.ts';

export function ServerProctoringMediaHarness({ attemptId, consentGranted, camera, screen, onChunk, onCaptureError, onEvent }: {
  attemptId: string;
  consentGranted: boolean;
  camera: MediaStream;
  screen: MediaStream;
  onChunk: (channel: 'camera' | 'screen', blob: Blob, sequence: number) => void;
  onCaptureError?: (message: string) => void;
  onEvent?: (type: 'camera_disabled' | 'screen_share_stopped') => void;
}) {
  const media = useProctoringMedia({ attemptId, consentGranted, captureMode: 'server', onChunk, onCaptureError, onEvent });
  media.setCameraStream(camera);
  media.setScreenStream(screen);
  return { start: () => media.startRecording(), stop: () => media.stopRecording(), get recordingError() { return media.recordingError; }, get isRecording() { return media.isRecording; } };
}
