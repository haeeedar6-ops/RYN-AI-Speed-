using System.Diagnostics.CodeAnalysis;
using System.Threading.Channels;

namespace RynSpeed.Core;

[SuppressMessage("Design", "CA1000:Do not declare static members on generic types", Justification = "Factory methods on immutable Result<T>")]
public sealed record RynResult<T>(bool IsSuccess, T? Value, string? ErrorCode, string? ErrorMessage)
{
    public static RynResult<T> Ok(T value) => new(true, value, null, null);
    public static RynResult<T> Fail(string code, string message) => new(false, default, code, message);
}

public static class FaultIsolation
{
    public static async Task<RynResult<T>> RunIsolatedAsync<T>(
        string errorCode,
        Func<CancellationToken, Task<T>> operation,
        CancellationToken cancellationToken = default)
    {
        try
        {
            var result = await operation(cancellationToken).ConfigureAwait(false);
            return RynResult<T>.Ok(result);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception ex)
        {
            return RynResult<T>.Fail(errorCode, ex.Message);
        }
    }
}

public abstract record ModuleEvent;
public sealed record ModuleDegradedEvent(string Module, string ErrorCode, string Reason) : ModuleEvent;
public sealed record NetworkStateChangedEvent(bool IsOnline, bool VpnActive) : ModuleEvent;
public sealed record DownloadProgressEvent(string DownloadId, long DownloadedBytes, long? TotalBytes) : ModuleEvent;

public sealed class ModuleEventBus
{
    private readonly Channel<ModuleEvent> _channel = Channel.CreateBounded<ModuleEvent>(
        new BoundedChannelOptions(128) { FullMode = BoundedChannelFullMode.DropOldest });

    public bool Publish(ModuleEvent evt) => _channel.Writer.TryWrite(evt);
    public IAsyncEnumerable<ModuleEvent> SubscribeAsync(CancellationToken ct) => _channel.Reader.ReadAllAsync(ct);
}
