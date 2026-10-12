export function isDemoProctoringEnabled(): boolean {
  return import.meta.env?.VITE_DEMO_PROCTORING === 'true';
}

export function canOfferDemoCapture({
  isDemoAttempt,
  processingAccepted,
  secureContext,
  mediaApisAvailable,
}: {
  isDemoAttempt: boolean;
  processingAccepted: boolean;
  secureContext: boolean;
  mediaApisAvailable: boolean;
}): boolean {
  return isDemoProctoringEnabled() && isDemoAttempt && processingAccepted && secureContext && mediaApisAvailable;
}
