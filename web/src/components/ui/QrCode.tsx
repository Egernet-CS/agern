import qrcode from 'qrcode-generator';
import { useMemo } from 'react';

/** QR code rendered locally as SVG. The otpauth URI (and its secret) never leaves the browser. */
export function QrCode({ value, size = 200, label }: { value: string; size?: number; label: string }) {
  const { n, d } = useMemo(() => {
    const qr = qrcode(0, 'M');
    qr.addData(value);
    qr.make();
    const count = qr.getModuleCount();
    let path = '';
    for (let r = 0; r < count; r++)
      for (let c = 0; c < count; c++) if (qr.isDark(r, c)) path += `M${c + 4},${r + 4}h1v1h-1z`;
    return { n: count + 8, d: path };
  }, [value]);
  return (
    <svg className="qr" viewBox={`0 0 ${n} ${n}`} width={size} height={size} role="img" aria-label={label} shapeRendering="crispEdges">
      <rect width={n} height={n} fill="#fff" />
      <path d={d} fill="#0b0e14" />
    </svg>
  );
}
