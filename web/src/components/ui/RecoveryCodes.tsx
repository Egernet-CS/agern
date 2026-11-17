import { CopyButton } from './ui';

export function RecoveryCodes({ codes, username }: { codes: string[]; username: string }) {
  const text = `Agern recovery codes for ${username}\nEach code works once. Generated ${new Date().toISOString().slice(0, 10)}.\n\n${codes.join('\n')}\n`;
  const download = () => {
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `agern-recovery-codes-${username}.txt`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  return (
    <div className="recovery">
      <ol className="recovery-codes" aria-label="Recovery codes">
        {codes.map((c) => (
          <li key={c}><code>{c}</code></li>
        ))}
      </ol>
      <div className="recovery-actions">
        <CopyButton text={codes.join('\n')} label="Copy all" />
        <button type="button" className="btn btn--ghost btn--sm" onClick={download}>Download .txt</button>
      </div>
    </div>
  );
}
