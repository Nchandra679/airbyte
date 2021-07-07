#
# MIT License
#
# Copyright (c) 2020 Airbyte
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.
#

import io

import docker
import pytest
from source_acceptance_test.utils import ConnectorRunner


def create_dockerfile(text, tag, tmp_path):
    client = docker.from_env()
    fileobj = io.BytesIO(bytes(text))
    args = {
        "fileobj": fileobj,
        "tag": tag,
        "forcerm": True,
        "rm": True,
    }
    image, iterools_tee = client.images.build(**args)
    docker_runner = ConnectorRunner(image_name=tag, volume=tmp_path)
    return docker_runner


@pytest.fixture
def dockerfile_valid(tmp_path):
    dockerfile_text = b"""
        FROM scratch
        ENV AIRBYTE_ENTRYPOINT "python /airbyte/integration_code/main.py"
        ENTRYPOINT ["python", "/airbyte/integration_code/main.py"]
        """
    docker_runner = create_dockerfile(dockerfile_text, "my-valid-one", tmp_path)
    return docker_runner


@pytest.fixture
def dockerfile_no_env(tmp_path):
    dockerfile_text = b"""
        FROM python:3.7-slim
        RUN apt-get update && apt-get install -y bash && rm -rf /var/lib/apt/lists/*
        ENTRYPOINT ["python", "/airbyte/integration_code/main.py"]
        """
    docker_runner = create_dockerfile(dockerfile_text, "my-no-env", tmp_path)
    return docker_runner


@pytest.fixture
def dockerfile_ne_properties(tmp_path):
    dockerfile_text = b"""
        FROM python:3.7-slim
        RUN apt-get update && apt-get install -y bash && rm -rf /var/lib/apt/lists/*
        ENV AIRBYTE_ENTRYPOINT "python /airbyte/integration_code/main.py"
        ENTRYPOINT ["python3", "/airbyte/integration_code/main.py"]
        """
    docker_runner = create_dockerfile(dockerfile_text, "my-ne-properties", tmp_path)
    return docker_runner


class TestEnvAttributes:
    def test_build_dockerfile_valid(self, dockerfile_valid):

        assert dockerfile_valid.env_variables.get("AIRBYTE_ENTRYPOINT"), "AIRBYTE_ENTRYPOINT must be set in dockerfile"
        assert dockerfile_valid.env_variables.get("AIRBYTE_ENTRYPOINT") == " ".join(
            dockerfile_valid.entry_point
        ), "env should be equal to space-joined entrypoint"

    def test_build_dockerfile_no_env(self, dockerfile_no_env):
        assert not dockerfile_no_env.env_variables.get("AIRBYTE_ENTRYPOINT"), "this test should fail if AIRBYTE_ENTRYPOINT defined"

    def test_build_dockerfile_ne_properties(self, dockerfile_ne_properties):

        assert dockerfile_ne_properties.env_variables.get("AIRBYTE_ENTRYPOINT"), "AIRBYTE_ENTRYPOINT must be set in dockerfile"
        assert dockerfile_ne_properties.env_variables.get("AIRBYTE_ENTRYPOINT") != " ".join(dockerfile_ne_properties.entry_point), (
            "This test should fail if we have " ".join(ENTRYPOINT)==ENV"
        )
