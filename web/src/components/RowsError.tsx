// Rows couldn't be personalised (the server answered 503: it couldn't tell who is asking). Never show unpersonalised
// rows instead: say so and offer a retry.
export function RowsError({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="rows-error" role="alert">
      <p>Your rows couldn't be loaded right now.</p>
      <button className="btn" data-nav data-autofocus onClick={onRetry}>
        Try again
      </button>
    </div>
  );
}
