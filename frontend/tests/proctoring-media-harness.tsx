import { useProctoringMedia } from '../src/features/proctoring/useProctoringMedia.ts';

export function ProctoringMediaHarness({
  attemptId,
  consentGranted,
  camera,
  screen,
  attachStreams = true,
}: {
  attemptId: string;
  consentGranted: boolean;
  camera: MediaStream;
  screen: MediaStream;
  attachStreams?: boolean;
}) {
  const media = useProctoringMedia({ attemptId, consentGranted });
  if (attachStreams) {
    media.setCameraStream(camera);
    media.setScreenStream(screen);
  }
  return {
    start: () => media.startRecording(),
    stop: () => media.stopRecording(),
  };
}
