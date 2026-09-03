import { useState, useEffect } from 'react';
import { FiX, FiCopy, FiCheck, FiFileText, FiCode, FiExternalLink, FiUploadCloud, FiClock } from 'react-icons/fi';
import { getFileContent } from '../api/github';
import './FileViewerModal.css';

export default function FileViewerModal({ isOpen, onClose, owner, repo, path, branch = 'main', onEditFile }) {
  const [fileData, setFileData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (isOpen && owner && repo && path) {
      fetchFile();
    } else {
      setFileData(null);
      setError(null);
    }
  }, [isOpen, owner, repo, path, branch]);

  async function fetchFile() {
    setLoading(true);
    setError(null);
    try {
      const data = await getFileContent(owner, repo, path, branch);
      setFileData(data);
    } catch (err) {
      setError(err.message || 'Failed to load file content');
    } finally {
      setLoading(false);
    }
  }

  function handleCopy() {
    if (fileData?.content) {
      navigator.clipboard.writeText(fileData.content);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  }

  function handleQuickEdit() {
    onClose?.();
    onEditFile?.({ path, content: fileData?.content || '' });
  }

  if (!isOpen) return null;

  const lines = fileData?.content ? fileData.content.split('\n') : [];
  const fileExt = path.includes('.') ? path.split('.').pop().toLowerCase() : '';

  return (
    <div className="modal-overlay" onClick={onClose} id="modal-file-viewer-overlay">
      <div
        className="modal-container file-viewer-modal glass-card"
        onClick={(e) => e.stopPropagation()}
        id="modal-file-viewer-content"
      >
        <div className="file-viewer-header">
          <div className="file-viewer-path">
            <FiFileText size={18} className="file-icon" />
            <span className="repo-prefix">{owner} / {repo} /</span>
            <span className="current-path">{path}</span>
          </div>

          <div className="file-viewer-actions">
            {fileData?.content && (
              <>
                <button
                  className="btn btn-secondary btn-sm"
                  onClick={handleCopy}
                  type="button"
                  id="btn-copy-code"
                >
                  {copied ? <><FiCheck size={14} /> Copied</> : <><FiCopy size={14} /> Copy</>}
                </button>

                <button
                  className="btn btn-primary btn-sm"
                  onClick={handleQuickEdit}
                  type="button"
                  id="btn-quick-edit"
                >
                  <FiUploadCloud size={14} />
                  Edit & Push
                </button>
              </>
            )}

            {fileData?.html_url && (
              <a
                href={fileData.html_url}
                target="_blank"
                rel="noopener noreferrer"
                className="btn btn-ghost btn-sm"
                title="View on GitHub"
              >
                <FiExternalLink size={16} />
              </a>
            )}

            <button className="modal-close-btn" onClick={onClose} type="button" id="btn-close-file-modal">
              <FiX size={20} />
            </button>
          </div>
        </div>

        <div className="file-viewer-bar">
          <span className="file-badge file-ext-badge">{fileExt ? fileExt.toUpperCase() : 'TEXT'}</span>
          {fileData?.size != null && <span>Size: {(fileData.size / 1024).toFixed(1)} KB ({fileData.size} bytes)</span>}
          {lines.length > 0 && <span>Lines: {lines.length}</span>}
          <span>Branch: {branch}</span>
        </div>

        <div className="file-viewer-body">
          {loading ? (
            <div className="file-viewer-loading">
              <div className="spinner"></div>
              <p>Fetching file content from GitHub...</p>
            </div>
          ) : error ? (
            <div className="file-viewer-error">
              <p>{error}</p>
              <button className="btn btn-secondary btn-sm" onClick={fetchFile}>
                Retry
              </button>
            </div>
          ) : fileData?.content != null ? (
            <div className="code-container">
              <div className="line-numbers">
                {lines.map((_, index) => (
                  <span key={index + 1}>{index + 1}</span>
                ))}
              </div>
              <pre className="code-content">
                <code>{fileData.content}</code>
              </pre>
            </div>
          ) : (
            <div className="file-viewer-empty">
              <p>File is empty.</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
