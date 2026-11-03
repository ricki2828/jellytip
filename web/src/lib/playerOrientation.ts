/** The rotate action alternates between portrait and landscape. */
export function nextPlayerOrientation(type: string): "portrait" | "landscape" {
  return type.startsWith("landscape") ? "portrait" : "landscape";
}

export function canOfferRotate(isNative: boolean, isPhone: boolean, canLockOrientation: boolean): boolean {
  return isNative || (isPhone && canLockOrientation);
}
