# Setup

1. Install the APK produced by GitHub Actions.
2. Connect a deployed compatible backend in Settings once the backend milestone is available.
3. Configure the model API key only in backend secrets, never inside the APK.
4. Add `COHERE_API_KEY` to GitHub Actions secrets to enable Cohere North Mini Code; add `AGNES_API_KEY` for Agnes models.
