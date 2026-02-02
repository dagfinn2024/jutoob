const browserAPI = typeof browser !== 'undefined' ? browser : chrome;
document.addEventListener('DOMContentLoaded', () => {
  const urlInput = document.getElementById('urlInput');
  const fetchBtn = document.getElementById('fetchBtn');
  const currentTabBtn = document.getElementById('currentTabBtn');
  const errorDiv = document.getElementById('error');
  const downloadSection = document.getElementById('downloadSection');
  const downloadIframe = document.getElementById('downloadIframe');

  const DOWNLOAD_BASE_URL = 'https://meowing.ssstik.art/download';

  function extractVideoId(url) {
    if (!url) return null;
    const patterns = [
      /(?:youtube\.com\/watch\?v=|youtu\.be\/|youtube\.com\/embed\/|youtube\.com\/v\/|youtube\.com\/shorts\/)([a-zA-Z0-9_-]{11})/,
      /^([a-zA-Z0-9_-]{11})$/
    ];
    for (const pattern of patterns) {
      const match = url.match(pattern);
      if (match) return match[1];
    }
    return null;
  }

  function showError(message) {
    errorDiv.textContent = message;
    errorDiv.classList.remove('hidden');
    downloadSection.classList.add('hidden');
    setTimeout(() => errorDiv.classList.add('hidden'), 5000);
  }

  function getToken() {
    return new Promise((resolve) => {
      browserAPI.runtime.sendMessage({ action: 'generateToken' }, (response) => {
        resolve(response?.success ? response.token : null);
      });
    });
  }

  async function showDownload(videoId, title = '') {
    const token = await getToken();
    if (!token) {
      showError('Failed to generate token');
      return;
    }

    const youtubeUrl = `https://youtube.com/watch?v=${videoId}`;
    const iframeSrc = `${DOWNLOAD_BASE_URL}?url=${encodeURIComponent(youtubeUrl)}&title=${encodeURIComponent(title)}&token=${token}`;

    downloadIframe.src = iframeSrc;
    downloadSection.classList.remove('hidden');
    errorDiv.classList.add('hidden');
  }

  function handleSubmit() {
    const url = urlInput.value.trim();
    if (!url) {
      showError('Please enter a YouTube URL');
      return;
    }
    const videoId = extractVideoId(url);
    if (!videoId) {
      showError('Invalid YouTube URL');
      return;
    }
    showDownload(videoId);
  }

  currentTabBtn.addEventListener('click', () => {
    browserAPI.tabs.query({ active: true, currentWindow: true }).then((tabs) => {
      if (tabs[0]?.url) {
        const videoId = extractVideoId(tabs[0].url);
        if (videoId) {
          urlInput.value = tabs[0].url;
          showDownload(videoId, tabs[0].title || '');
        } else {
          showError('Current tab is not a YouTube video');
        }
      }
    });
  });
  browserAPI.tabs.query({ active: true, currentWindow: true }).then((tabs) => {
    if (tabs[0]?.url) {
      const videoId = extractVideoId(tabs[0].url);
      if (videoId) urlInput.value = tabs[0].url;
    }
  });
  setTimeout(() => {handleSubmit();}, 100);
});
